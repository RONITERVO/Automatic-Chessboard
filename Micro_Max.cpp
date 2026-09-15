/*
 * Micro-Max adaptation used by the modified Automatic Chessboard firmware.
 * Micro-Max was written by H. G. Muller; this Arduino adaptation descends
 * from the copy distributed with "Automated Chessboard" by Greg06.
 * See ATTRIBUTION.md and LICENSE.md for sources and licensing qualifications.
 */

//******************************  INCLUDING FILES
#include "Arduino.h"
#include "Micro_Max.h"

//************************************  VARIABLES
#define W while
#define M 0x88
#define S 128
#define I 8000

#define MYRAND_MAX 65535     /* 16bit pseudo random generator */
// The compiled Nano D() frame is 89 bytes and only about 800 bytes of SRAM are
// free when search begins. A seventh active search body would leave too little
// room for callers and interrupts; its entry is allowed only to return the
// static evaluation. The Mega profile has enough SRAM for the original limit.
#if defined(ACB_PROFILE_MKS_GEN_L_V1)
#define MICRO_MAX_RECURSION_LIMIT 30
#else
#define MICRO_MAX_RECURSION_LIMIT 6
#endif

long  N, T;                  /* N=evaluated positions+S, T=recursion limit */
short Q, O, K, R, k = 16;    /* k=moving side */
char *p, c[5], Z;            /* p=pointer to c, c=user input, computer output, Z=recursion counter */

char L,
     w[] = {0, 2, 2, 7, -1, 8, 12, 23},                    /* relative piece values    */
           o[] = { -16, -15, -17, 0, 1, 16, 0, 1, 16, 15, 17, 0, 14, 18, 31, 33, 0, /* step-vector lists */
                   7, -1, 11, 6, 8, 3, 6,                           /* 1st dir. in o[] per piece*/
                   6, 3, 5, 7, 4, 5, 3, 6
                 };                                /* initial piece setup      */
/* board is left part, center-pts table is right part, and dummy */

#define INITIAL_BOARD_VALUES \
  22, 19, 21, 23, 20, 21, 19, 22, 28, 21, 16, 13, 12, 13, 16, 21, \
  18, 18, 18, 18, 18, 18, 18, 18, 22, 15, 10,  7,  6,  7, 10, 15, \
  0,  0,  0,  0,  0,  0,  0,  0, 18, 11,  6,  3,  2,  3,  6, 11, \
  0,  0,  0,  0,  0,  0,  0,  0, 16,  9,  4,  1,  0,  1,  4,  9, \
  0,  0,  0,  0,  0,  0,  0,  0, 16,  9,  4,  1,  0,  1,  4,  9, \
  0,  0,  0,  0,  0,  0,  0,  0, 18, 11,  6,  3,  2,  3,  6, 11, \
  9,  9,  9,  9,  9,  9,  9,  9, 22, 15, 10,  7,  6,  7, 10, 15, \
  14, 11, 13, 15, 12, 13, 11, 14, 28, 21, 16, 13, 12, 13, 16, 21, 0

char b[] = {INITIAL_BOARD_VALUES};
const char initialBoard[] PROGMEM = {INITIAL_BOARD_VALUES};

unsigned int seed = 0;
char lastM[5] = {0, 0, 0, 0, 0};

int r;

//***************************************  MYRAND
unsigned short myrand(void) {
  unsigned short r = (unsigned short)(seed % MYRAND_MAX);
  return r = ((r << 11) + (r << 7) + r) >> 1;
}
/* recursive minimax search */
/* (q,l)=window, e=current eval. score, */
/* E=e.p. sqr.z=prev.dest, n=depth; return score */
short D(short q, short l, short e, unsigned char E, unsigned char z, unsigned char n) {
  short m = 0, v, i, P, V, s;
  unsigned char t, p, u, x, y, X, Y, H, B, j, d, h, F, G, C;
  signed char r;
  if (++Z > MICRO_MAX_RECURSION_LIMIT) {            /* stack underrun check */
    --Z; return e;
  }
  q--;                                          /* adj. window: delay bonus */
  k ^= 24;                                      /* change sides             */
  d = Y = 0;                                    /* start iter. from scratch */
  X = myrand() & ~M;                            /* start at random field    */
  W(d++ < n || d < 3 ||                         /* iterative deepening loop */
    z & K == I && (N < T & d < 98 ||            /* root: deepen upto time   */
                   (K = X, L = Y & ~M, d = 3)))                /* time's up: go do best    */
  { x = B = X;                                   /* start scan at prev. best */
    h = Y & S;                                   /* request try noncastl. 1st*/
    P = d < 3 ? I : D(-l, 1 - l, -e, S, 0, d - 3); /* Search null move         */
    m = -P < l | R > 35 ? d > 2 ? -I : e : -P;   /* Prune or stand-pat       */
    ++N;                                         /* node count (for timing)  */
    do {
      u = b[x];                                   /* scan board looking for   */
      if (u & k) {                                /*  own piece (inefficient!)*/
        r = p = u & 7;                             /* p = piece type (set r>0) */
        j = o[p + 16];                             /* first step vector f.piece*/
        W(r = p > 2 & r < 0 ? -r : -o[++j])        /* loop over directions o[] */
        { A:                                        /* resume normal after best */
          y = x; F = G = S;                         /* (x,y)=move, (F,G)=castl.R*/
          do {                                      /* y traverses ray, or:     */
            H = y = h ? Y ^ h : y + r;               /* sneak in prev. best move */
            if (y & M)break;                         /* board edge hit           */
            m = E - S & b[E] && y - E < 2 & E - y < 2 ? I : m; /* bad castling             */
            if (p < 3 & y == E)H ^= 16;              /* shift capt.sqr. H if e.p.*/
            t = b[H]; if (t & k | p < 3 & !(y - x & 7) - !t)break; /* capt. own, bad pawn mode */
            i = 37 * w[t & 7] + (t & 192);           /* value of capt. piece t   */
            m = i < 0 ? I : m;                       /* K capture                */
            if (m >= l & d > 1)goto C;               /* abort on fail high       */
            v = d - 1 ? e : i - p;                   /* MVV/LVA scoring          */
            if (d - !t > 1)                          /* remaining depth          */
            { v = p < 6 ? b[x + 8] - b[y + 8] : 0;    /* center positional pts.   */
              b[G] = b[H] = b[x] = 0; b[y] = u | 32;  /* do move, set non-virgin  */
              if (!(G & M))b[F] = k + 6, v += 50;     /* castling: put R & score  */
              v -= p - 4 | R > 29 ? 0 : 20;           /* penalize mid-game K move */
              if (p < 3)                              /* pawns:                   */
              { v -= 9 * ((x - 2 & M || b[x - 2] - u) + /* structure, undefended    */
                          (x + 2 & M || b[x + 2] - u) - 1  /*        squares plus bias */
                          + (b[x ^ 16] == k + 36))          /* kling to non-virgin King */
                     - (R >> 2);                       /* end-game Pawn-push bonus */
                V = y + r + 1 & S ? 647 - p : 2 * (u & y + 16 & 32); /* promotion or 6/7th bonus */
                b[y] += V; i += V;                     /* change piece, add score  */
              }
              v += e + i; V = m > q ? m : q;          /* new eval and alpha       */
              C = d - 1 - (d > 5 & p > 2 & !t & !h);
              C = R > 29 | d < 3 | P - I ? C : d;     /* extend 1 ply if in check */
              do
                s = C > 2 | v > V ? -D(-l, -V, -v,     /* recursive eval. of reply */
                                       F, 0, C) : v;    /* or fail low if futile    */
              W(s > q&++C < d); v = s;
              if (z && K - I && v + I && x == K & y == L) /* move pending & in root:  */
              { Q = -e - i; O = F;                     /*   exit if legal & found  */
                R += i >> 7; --Z; return l;            /* captured non-P material  */
              }
              b[G] = k + 6; b[F] = b[y] = 0; b[x] = u; b[H] = t; /* undo move,G can be dummy */
            }
            if (v > m)                               /* new best, update max,best*/
              m = v, X = x, Y = y | S & F;            /* mark double move with S  */
            if (h) {
              h = 0;  /* redo after doing old best*/
              goto A;
            }
            if (x + r - y | u & 32 |                 /* not 1st step,moved before*/
                p > 2 & (p - 4 | j - 7 ||             /* no P & no lateral K move,*/
                         b[G = x + 3 ^ r >> 1 & 7] - k - 6     /* no virgin R in corner G, */
                         || b[G ^ 1] | b[G ^ 2])               /* no 2 empty sq. next to R */
               )t += p < 5;                           /* fake capt. for nonsliding*/
            else F = y;                              /* enable e.p.              */
          } W(!t);                                  /* if not capt. continue ray*/
        }
      }
    } W((x = x + 9 & ~M) - B);                 /* next sqr. of board, wrap */
C: if (m > I - M | m < M - I)d = 98;           /* mate holds to any depth  */
    m = m + I | P == I ? m : 0;                  /* best loses K: (stale)mate*/
    if (z && d > 2)
    { *c = 'a' + (X & 7); c[1] = '8' - (X >> 4); c[2] = 'a' + (Y & 7); c[3] = '8' - (Y >> 4 & 7); c[4] = 0; }
  }                                             /*    encoded in X S,8 bits */
  k ^= 24;                                      /* change sides back        */
  --Z; return m += m < e;                       /* delayed-loss bonus       */
}
// Restore a fresh chess engine so GAME can safely be started after service mode.
void AI_reset() {
  for (unsigned int i = 0; i < sizeof(b); i++) {
    b[i] = (char)pgm_read_byte(&initialBoard[i]);
  }
  N = T = 0;
  Q = O = K = R = 0;
  k = 16;
  L = Z = 0;
  seed = 0;
  for (byte i = 0; i < 5; i++) {
    c[i] = 0;
    lastM[i] = 0;
  }
}

// Validate the human move, calculate the black reply, and apply it internally.
byte AI_pieceAt(byte square) {
  return b[(square & 7) + (square >> 3) * 16];
}

// Companion games use their full rules engine. Keep the existing piece table
// current for LCD suggestions without allocating a second board on the Nano.
void AI_applyRemoteMove(const char *move, char promotion) {
  byte from = (8 - (move[1] - '0')) * 16 + move[0] - 'a';
  byte to = (8 - (move[3] - '0')) * 16 + move[2] - 'a';
  byte piece = b[from], type = piece & 7;
  if (type < 3 && (from & 7) != (to & 7) && !b[to])
    b[(from & 0x70) | (to & 7)] = 0;
  if (type == 4 && abs((int)to - from) == 2) {
    byte rook = (from & 0x70) | (to > from ? 7 : 0);
    b[(from + to) / 2] = b[rook] | 32;
    b[rook] = 0;
  }
  if (type < 3 && (to < 8 || to >= 112)) {
    byte promoted = promotion == 'n' ? 3 : promotion == 'b' ? 5 : promotion == 'r' ? 6 : 7;
    piece = (piece & 24) | promoted;
  }
  b[from] = 0;
  b[to] = piece | 32;
}

void AI_copyOccupancy(byte *rows) {
  for (byte row = 0; row < 8; row++) {
    rows[row] = 0;
    for (byte file = 0; file < 8; file++)
      if (b[row * 16 + file]) rows[row] |= 1 << file;
  }
}

// A compact suggestion filter, not the final legality check. Micro-Max (or
// the companion rules engine) still validates the explicitly confirmed move.
boolean AI_movePossible(byte from, byte to, boolean white) {
  byte piece = AI_pieceAt(from), target = AI_pieceAt(to);
  byte side = white ? 8 : 16;
  if (from == to || !(piece & side) || (target & side)) return false;
  signed char dx = (to & 7) - (from & 7);
  signed char dy = (to >> 3) - (from >> 3);
  byte ax = abs(dx), ay = abs(dy), type = piece & 7;
  if (type < 3) {
    signed char forward = white ? -1 : 1;
    if (ax == 1 && dy == forward)
      return target || ((from >> 3) == (white ? 3 : 4) &&
          (AI_pieceAt((from & 56) | (to & 7)) & 7) == (white ? 2 : 1));
    if (dx || target) return false;
    if (dy == forward) return true;
    return dy == 2 * forward && (from >> 3) == (white ? 6 : 1) &&
           !AI_pieceAt(from + 8 * forward);
  }
  if (type == 3) return (ax == 1 && ay == 2) || (ax == 2 && ay == 1);
  if (type == 4 && ax <= 1 && ay <= 1) return true;
  if (type == 4) {
    if ((piece & 32) || (from & 7) != 4 || dy || ax != 2) return false;
  }
  else if (type == 5 ? ax != ay : type == 6 ? (dx && dy) : (dx && dy && ax != ay))
    return false;
  int step = (dy == 0 ? 0 : dy > 0 ? 8 : -8) + (dx == 0 ? 0 : dx > 0 ? 1 : -1);
  for (int square = from + step; square != to; square += step)
    if (AI_pieceAt(square)) return false;
  return true;
}

// The caller moves the physical black piece only when AI_MOVE_READY is returned.
byte AI_HvsC(byte *human_rows) {
  for (byte i = 0; i < 4; i++) c[i] = mov[i];
  c[4] = 0;

  K = *c - 16 * c[1] + 799;
  L = c[2] - 16 * c[3] + 799;
  N = 0;
  T = 0x3F;
  r = D(-I, I, Q, O, 1, 3);
  if (!(r > -I + 1)) return AI_GAME_OVER;
  if (k == 0x10) return AI_INVALID_MOVE;
  AI_copyOccupancy(human_rows);

  K = I;
  N = 0;
  T = 0x3F;
  r = D(-I, I, Q, O, 1, 3);
  if (!(r > -I + 1)) return AI_GAME_OVER;

  for (byte i = 0; i < 5; i++) lastM[i] = c[i];
  r = D(-I, I, Q, O, 1, 3);
  if (!(r > -I + 1)) return AI_GAME_OVER;
  return AI_MOVE_READY;
}
