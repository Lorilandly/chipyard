// See LICENSE.Sifive for license details.

#ifndef _SIFIVE_CONST_H
#define _SIFIVE_CONST_H

#ifdef __ASSEMBLER__
#define _AC(X,Y)        X
#else
#define _AC(X,Y)        (X##Y)
#endif /* !__ASSEMBLER__*/

#endif /* _SIFIVE_CONST_H */
