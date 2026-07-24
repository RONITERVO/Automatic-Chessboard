#ifndef MICRO_MAX_H
#define MICRO_MAX_H

enum {AI_INVALID_MOVE, AI_MOVE_READY, AI_GAME_OVER};

unsigned short myrand(void);
short D(short q, short l, short e, unsigned char E, unsigned char z, unsigned char n);
void AI_reset();
byte AI_HvsC();
byte AI_selfPlayMove();

extern char mov[];
extern char lastM[];

#endif
