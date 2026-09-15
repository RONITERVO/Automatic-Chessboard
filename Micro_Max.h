/*
 * Interface for the local Micro-Max adaptation.
 * See ATTRIBUTION.md and LICENSE.md for sources and licensing qualifications.
 */

#ifndef MICRO_MAX_H
#define MICRO_MAX_H

enum {AI_INVALID_MOVE, AI_MOVE_READY, AI_GAME_OVER};

unsigned short myrand(void);
short D(short q, short l, short e, unsigned char E, unsigned char z, unsigned char n);
void AI_reset();
byte AI_HvsC(byte *human_rows);
byte AI_pieceAt(byte square);
boolean AI_movePossible(byte from, byte to, boolean white);
void AI_copyOccupancy(byte *rows);
void AI_applyRemoteMove(const char *move, char promotion);

extern char mov[];
extern char lastM[];

#endif
