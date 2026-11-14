/*
 * A process tree that loads like a game under Wine, for SessionSuspendRealProcessTest.
 *
 *   root (session root without proot; the first tracee under proot)
 *   +- server: a main thread and two threads that wait in timed condition and poll calls
 *   +- game:   a main thread that keeps forking and reaping short-lived children,
 *              a loader thread that keeps starting and joining short-lived threads and adds
 *              one long-lived worker every WORKER_INTERVAL_MS until there are WORKERS of them;
 *              the first BUSY workers spin, the others sleep in short naps,
 *              and a collector thread that stops every worker the way Wine's SuspendThread does
 *              (SIGUSR1, then the handler blocks until told to go on), as a .NET garbage collection would
 *
 * Every long-lived thread bumps its own 64-bit slot in a file mapped MAP_SHARED, so the test can
 * see which threads still run by reading the file. Usage: game_tree COUNTERS WORKERS
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

enum { ROOT, SERVER_MAIN, SERVER_COND, SERVER_POLL, GAME_MAIN, GAME_LOADER, GAME_TRANSIENT, GAME_COLLECTOR, FIRST_WORKER };
#define BUSY 4
#define WORKER_INTERVAL_MS 60
#define LIFETIME_S 300

#define MAX_WORKERS 256
#define COLLECT_INTERVAL_US 20000

static volatile uint64_t *slots;
static int workers;
static int started;
static pid_t worker_tids[MAX_WORKERS];
static int suspend_ack[2], suspend_resume[2], suspend_done[2];

static void bump(int slot) { __atomic_fetch_add(&slots[slot], 1, __ATOMIC_RELAXED); }

static void nap_us(long us) {
    struct timespec ts = { us / 1000000, (us % 1000000) * 1000 };
    while (nanosleep(&ts, &ts) == -1 && errno == EINTR) {}
}

static void as_child(const char *name) {
    prctl(PR_SET_PDEATHSIG, SIGKILL);
    prctl(PR_SET_NAME, name);
    alarm(LIFETIME_S);
}

static void *server_cond(void *unused) {
    pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
    pthread_cond_t cond = PTHREAD_COND_INITIALIZER;
    prctl(PR_SET_NAME, "server-cond");
    pthread_mutex_lock(&mutex);
    for (;;) {
        struct timespec until;
        clock_gettime(CLOCK_REALTIME, &until);
        until.tv_nsec += 5 * 1000000;
        if (until.tv_nsec >= 1000000000) { until.tv_sec++; until.tv_nsec -= 1000000000; }
        pthread_cond_timedwait(&cond, &mutex, &until);
        bump(SERVER_COND);
    }
    return unused;
}

static void *server_poll(void *unused) {
    int fds[2];
    prctl(PR_SET_NAME, "server-poll");
    if (pipe(fds) != 0) abort();
    for (;;) {
        struct pollfd fd = { fds[0], POLLIN, 0 };
        poll(&fd, 1, 3);
        bump(SERVER_POLL);
    }
    return unused;
}

static void server(void) {
    pthread_t thread;
    as_child("server");
    pthread_create(&thread, NULL, server_cond, NULL);
    pthread_create(&thread, NULL, server_poll, NULL);
    for (;;) {
        nap_us(10000);
        bump(SERVER_MAIN);
    }
}

static void on_suspend(int signal) {
    int saved = errno;
    char byte = (char)signal;
    while (write(suspend_ack[1], &byte, 1) == -1 && errno == EINTR) {}
    while (read(suspend_resume[0], &byte, 1) == -1 && errno == EINTR) {}
    while (write(suspend_done[1], &byte, 1) == -1 && errno == EINTR) {}
    errno = saved;
}

static void *collector(void *unused) {
    prctl(PR_SET_NAME, "collector");
    for (;;) {
        nap_us(COLLECT_INTERVAL_US);
        int count = __atomic_load_n(&started, __ATOMIC_ACQUIRE), signalled = 0;
        char byte;
        for (int i = 0; i < count; i++) {
            pid_t tid = __atomic_load_n(&worker_tids[i], __ATOMIC_ACQUIRE);
            if (tid > 0 && syscall(SYS_tgkill, getpid(), tid, SIGUSR1) == 0) signalled++;
        }
        for (int i = 0; i < signalled; i++) while (read(suspend_ack[0], &byte, 1) == -1 && errno == EINTR) {}
        nap_us(500);
        for (int i = 0; i < signalled; i++) while (write(suspend_resume[1], &byte, 1) == -1 && errno == EINTR) {}
        for (int i = 0; i < signalled; i++) while (read(suspend_done[0], &byte, 1) == -1 && errno == EINTR) {}
        bump(GAME_COLLECTOR);
    }
    return unused;
}

static void *worker(void *arg) {
    int index = (int)(intptr_t)arg;
    char name[16];
    __atomic_store_n(&worker_tids[index], (pid_t)syscall(SYS_gettid), __ATOMIC_RELEASE);
    snprintf(name, sizeof name, "worker-%d", index);
    prctl(PR_SET_NAME, name);
    for (uint64_t n = 0;; n++) {
        bump(FIRST_WORKER + index);
        if (index >= BUSY) nap_us(2000);
        else if (n % 4096 == 0) sched_yield();
    }
    return NULL;
}

static void *transient(void *unused) {
    bump(GAME_TRANSIENT);
    return unused;
}

static void *loader(void *unused) {
    struct timespec start, now;
    prctl(PR_SET_NAME, "loader");
    clock_gettime(CLOCK_MONOTONIC, &start);
    for (;;) {
        pthread_t thread;
        if (pthread_create(&thread, NULL, transient, NULL) == 0) pthread_join(thread, NULL);
        clock_gettime(CLOCK_MONOTONIC, &now);
        long elapsed = (now.tv_sec - start.tv_sec) * 1000 + (now.tv_nsec - start.tv_nsec) / 1000000;
        while (started < workers && elapsed >= (long)started * WORKER_INTERVAL_MS) {
            pthread_attr_t attr;
            pthread_attr_init(&attr);
            pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
            if (pthread_create(&thread, &attr, worker, (void *)(intptr_t)started) != 0) abort();
            pthread_attr_destroy(&attr);
            __atomic_store_n(&started, started + 1, __ATOMIC_RELEASE);
        }
        bump(GAME_LOADER);
        nap_us(1000);
    }
    return unused;
}

static void game(void) {
    pthread_t thread;
    struct sigaction action = { 0 };
    as_child("game");
    if (pipe(suspend_ack) != 0 || pipe(suspend_resume) != 0 || pipe(suspend_done) != 0) abort();
    action.sa_handler = on_suspend;
    action.sa_flags = SA_RESTART;
    sigemptyset(&action.sa_mask);
    sigaction(SIGUSR1, &action, NULL);
    pthread_create(&thread, NULL, loader, NULL);
    pthread_create(&thread, NULL, collector, NULL);
    for (uint64_t n = 0;; n++) {
        if (n % 10 == 0) {
            pid_t child = fork();
            if (child == 0) {
                prctl(PR_SET_PDEATHSIG, SIGKILL);
                _exit(0);
            }
            if (child > 0) while (waitpid(child, NULL, 0) == -1 && errno == EINTR) {}
        }
        nap_us(10000);
        bump(GAME_MAIN);
    }
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s COUNTERS WORKERS\n", argv[0]);
        return 2;
    }
    workers = atoi(argv[2]);
    if (workers < 1 || workers > MAX_WORKERS) return 2;
    size_t size = sizeof(uint64_t) * (FIRST_WORKER + workers);
    int fd = open(argv[1], O_RDWR | O_CREAT | O_TRUNC, 0644);
    if (fd < 0 || ftruncate(fd, size) != 0) { perror("counters"); return 1; }
    slots = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (slots == MAP_FAILED) { perror("mmap"); return 1; }
    close(fd);
    alarm(LIFETIME_S);
    prctl(PR_SET_CHILD_SUBREAPER, 1);
    if (fork() == 0) server();
    if (fork() == 0) game();
    for (;;) {
        while (waitpid(-1, NULL, WNOHANG) > 0) {}
        nap_us(10000);
        bump(ROOT);
    }
}
