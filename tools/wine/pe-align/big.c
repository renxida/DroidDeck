/* Stand-in for a Godot export with its .pck embedded in the .exe: a large read-only data section after the code.
 * Prints how long the process took to get here, then waits so the caller can read the process's smaps. */
#include <stdio.h>
#include <stdlib.h>
#include <windows.h>

__asm__(".section .pck,\"dr\"\n"
        ".globl pck_start\npck_start:\n"
        ".incbin \"" PCK_BLOB "\"\n"
        ".globl pck_end\npck_end:\n"
        ".text\n");
extern const unsigned char pck_start[], pck_end[];

int main(int argc, char **argv)
{
    int wait = argc > 1 ? atoi(argv[1]) : 20;
    printf("pck %p-%p (%zu MB)\n", pck_start, pck_end, (size_t)(pck_end - pck_start) >> 20);
    if (argc > 2) { /* touch every page: what reading the section would cost */
        unsigned sum = 0;
        for (const unsigned char *p = pck_start; p < pck_end; p += 4096) sum += *p;
        printf("touched, sum %u\n", sum);
    }
    printf("ready\n");
    fflush(stdout);
    Sleep(wait * 1000);
    return 0;
}
