/*
 * Sends signals for SessionSuspendRealProcessTest, the way the app does on the phone.
 * Reads one request per line from stdin and answers each with 0 or -errno:
 *   k PID SIGNAL        kill(PID, SIGNAL)
 *   t TGID TID SIGNAL   tgkill(TGID, TID, SIGNAL)
 */
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(void) {
    char kind;
    int a, b, c;
    setvbuf(stdout, NULL, _IOLBF, 0);
    while (scanf(" %c", &kind) == 1) {
        long result;
        if (kind == 'k' && scanf("%d %d", &a, &b) == 2) {
            result = kill(a, b);
        } else if (kind == 't' && scanf("%d %d %d", &a, &b, &c) == 3) {
            result = syscall(SYS_tgkill, a, b, c);
        } else {
            return 2;
        }
        printf("%ld\n", result == 0 ? 0L : -(long)errno);
    }
    return 0;
}
