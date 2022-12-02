#include <spi/spi.h>
#include <sd/sd.h>
#include <gpt/gpt.h>
#include <uart/uart.h>
#include <include/platform.h>


#ifndef PAYLOAD_DEST
  #define PAYLOAD_DEST MEMORY_MEM_ADDR
#endif
#ifndef TL_CLK
  #error Must define TL_CLK
#endif
#define F_CLK TL_CLK

#define GPT_BLOCK_SIZE 512

#define ERROR_CODE_GPT_PARTITION_NOT_FOUND 0x3
#define ERROR_CODE_SD_CARD_CMD0 0x5
#define ERROR_CODE_SD_CARD_CMD8 0x6
#define ERROR_CODE_SD_CARD_ACMD41 0x7
#define ERROR_CODE_SD_CARD_CMD58 0x8
#define ERROR_CODE_SD_CARD_CMD16 0x9
#define ERROR_CODE_SD_CARD_CMD18 0xa
#define ERROR_CODE_SD_CARD_CMD18_CRC 0xb
#define ERROR_CODE_SD_CARD_UNEXPECTED_ERROR 0xc
// Total payload in B
#define PAYLOAD_SIZE_B (30 << 20) // default: 30MiB
// A sector is 512 bytes, so (1 << 11) * 512B = 1 MiB
#define SECTOR_SIZE_B 512
// Payload size in # of sectors
#define PAYLOAD_SIZE (PAYLOAD_SIZE_B / SECTOR_SIZE_B)

void boot_fail(long code)
{
  uint64_t error_code = code;
  uart_puts((void*) UART0_CTRL_ADDR, "Error 0x");
  uart_put_hex((void*) UART0_CTRL_ADDR, error_code);
  /*
  if (read_csr(mhartid) == NONSMP_HART) {
    // Print error code to UART
    UART0_REG(UART_REG_TXCTRL) = UART_TXEN;

    // Error codes are formatted as follows:
    // [63:60]    [59:56]  [55:0]
    // bootstage  trap     errorcode
    // If trap == 1, then errorcode is actually the mcause register with the
    // interrupt bit shifted to bit 55.
    uint64_t error_code = 0;
    if (trap) {
      error_code = INSERT_FIELD(error_code, ERROR_CODE_ERRORCODE_MCAUSE_CAUSE, code);
      if (code < 0) {
        error_code = INSERT_FIELD(error_code, ERROR_CODE_ERRORCODE_MCAUSE_INT, 0x1UL);
      }
    } else {
      error_code = code;
    }
    uint64_t formatted_code = 0;
    formatted_code = INSERT_FIELD(formatted_code, ERROR_CODE_BOOTSTAGE, UX00BOOT_BOOT_STAGE);
    formatted_code = INSERT_FIELD(formatted_code, ERROR_CODE_TRAP, trap);
    formatted_code = INSERT_FIELD(formatted_code, ERROR_CODE_ERRORCODE, error_code);

    uart_puts((void*) UART0_CTRL_ADDR, "Error 0x");
    uart_put_hex((void*) UART0_CTRL_ADDR, formatted_code >> 32);
    uart_put_hex((void*) UART0_CTRL_ADDR, formatted_code);
  }
  */

  while (1);
}


int puts(const char * str){
	uart_puts((void *) UART0_CTRL_ADDR, str);
	return 1;
}


//------------------------------------------------------------------------------
// SD Card
//------------------------------------------------------------------------------

static int initialize_sd(spi_ctrl* spictrl, unsigned int peripheral_input_khz)
{
  int error = sd_init(spictrl, peripheral_input_khz);
  if (error) {
    switch (error) {
      case SD_INIT_ERROR_CMD0: return ERROR_CODE_SD_CARD_CMD0;
      case SD_INIT_ERROR_CMD8: return ERROR_CODE_SD_CARD_CMD8;
      case SD_INIT_ERROR_ACMD41: return ERROR_CODE_SD_CARD_ACMD41;
      case SD_INIT_ERROR_CMD58: return ERROR_CODE_SD_CARD_CMD58;
      case SD_INIT_ERROR_CMD16: return ERROR_CODE_SD_CARD_CMD16;
      default: return ERROR_CODE_SD_CARD_UNEXPECTED_ERROR;
    }
  }
  return 0;
}


static gpt_partition_range find_sd_gpt_partition(
  spi_ctrl* spictrl,
  uint64_t partition_entries_lba,
  uint32_t num_partition_entries,
  uint32_t partition_entry_size,
  const gpt_guid* partition_type_guid,
  void* block_buf  // Used to temporarily load blocks of SD card
)
{
  // Exclusive end
  uint64_t partition_entries_lba_end = (
    partition_entries_lba +
    (num_partition_entries * partition_entry_size + GPT_BLOCK_SIZE - 1) / GPT_BLOCK_SIZE
  );
  for (uint64_t i = partition_entries_lba; i < partition_entries_lba_end; i++) {
    sd_copy(spictrl, block_buf, i, 1);
    gpt_partition_range range = gpt_find_partition_by_guid(
      block_buf, partition_type_guid, GPT_BLOCK_SIZE / partition_entry_size
    );
    if (gpt_is_valid_partition_range(range)) {
      return range;
    }
  }
  return gpt_invalid_partition_range();
}


static int decode_sd_copy_error(int error)
{
  switch (error) {
    case SD_COPY_ERROR_CMD18: return ERROR_CODE_SD_CARD_CMD18;
    case SD_COPY_ERROR_CMD18_CRC: return ERROR_CODE_SD_CARD_CMD18_CRC;
    default: return ERROR_CODE_SD_CARD_UNEXPECTED_ERROR;
  }
}


static int load_sd_gpt_partition(spi_ctrl* spictrl, void* dst, const gpt_guid* partition_type_guid)
{
  uint8_t gpt_buf[GPT_BLOCK_SIZE];
  int error;
  error = sd_copy(spictrl, gpt_buf, GPT_HEADER_LBA, 1);
  if (error) return decode_sd_copy_error(error);

  gpt_partition_range part_range;
  {
    // header will be overwritten by find_sd_gpt_partition(), so locally
    // scope it.
    gpt_header* header = (gpt_header*) gpt_buf;
    part_range = find_sd_gpt_partition(
      spictrl,
      header->partition_entries_lba,
      header->num_partition_entries,
      header->partition_entry_size,
      partition_type_guid,
      gpt_buf
    );
  }

  if (!gpt_is_valid_partition_range(part_range)) {
    return ERROR_CODE_GPT_PARTITION_NOT_FOUND;
  }

  error = sd_copy(
    spictrl,
    dst,
    part_range.first_lba,
    part_range.last_lba + 1 - part_range.first_lba
  );
  if (error) return decode_sd_copy_error(error);
  return 0;
}


int main(void) {
  uart_puts((void*) UART0_CTRL_ADDR, "INIT");
  spi_ctrl* spictrl = NULL;
  unsigned long peripheral_input_khz = F_CLK;
  unsigned int error = 0;
  const gpt_guid partition_type_guid = {{
    0x28, 0x73, 0x2a, 0xc1, 0x1f, 0xf8, 0xd2, 0x11, 0xba, 0x4b, 0x00, 0xa0, 0xc9, 0x3e, 0xc9, 0x3b
  }};

  spictrl = (spi_ctrl*) SPI0_CTRL_ADDR;
  void *dst =  (void *)(PAYLOAD_DEST);
  error = initialize_sd(spictrl, peripheral_input_khz);
  if (!error) error = load_sd_gpt_partition(spictrl, dst, &partition_type_guid);
  
  if (error) {
    boot_fail(error);
  }
}