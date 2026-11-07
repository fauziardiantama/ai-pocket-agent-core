#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <dirent.h>
#include <limits.h>

#define BLOCK_SIZE 512
static const char OLD_ID[10] = "com.termux";
static const char NEW_ID[10] = "ai.pa.core";
static const size_t ID_LEN = 10;

static const char *get_real_dpkg_deb(void) {
    static char path[PATH_MAX];
    const char *prefix = getenv("PREFIX");
    if (prefix && prefix[0] != '\0') {
        int ret = snprintf(path, sizeof(path), "%s/bin/dpkg-deb.real", prefix);
        if (ret > 0 && (size_t)ret < sizeof(path) && access(path, X_OK) == 0) {
            return path;
        }
    }
    const char *canonical = "/data/data/ai.pa.core/files/usr/bin/dpkg-deb.real";
    if (access(canonical, X_OK) == 0) {
        return canonical;
    }
    return canonical;
}

static ssize_t read_exact(int fd, void *buf, size_t count) {
    size_t total = 0;
    while (total < count) {
        ssize_t n = read(fd, (char *)buf + total, count - total);
        if (n > 0) {
            total += (size_t)n;
        } else if (n == 0) {
            return (ssize_t)total;
        } else {
            if (errno == EINTR) continue;
            return -1;
        }
    }
    return (ssize_t)total;
}

static ssize_t write_exact(int fd, const void *buf, size_t count) {
    size_t total = 0;
    while (total < count) {
        ssize_t n = write(fd, (const char *)buf + total, count - total);
        if (n > 0) {
            total += (size_t)n;
        } else {
            if (errno == EINTR) continue;
            if (errno == EPIPE) return -1;
            return -1;
        }
    }
    return (ssize_t)total;
}

static int replace_bytes(unsigned char *buf, size_t len, const char *old_str, const char *new_str, size_t str_len) {
    int count = 0;
    if (len < str_len) return 0;
    for (size_t i = 0; i <= len - str_len; i++) {
        if (memcmp(buf + i, old_str, str_len) == 0) {
            memcpy(buf + i, new_str, str_len);
            count++;
            i += str_len - 1;
        }
    }
    return count;
}

static unsigned int calculate_tar_checksum(const unsigned char *header) {
    unsigned int sum = 0;
    for (size_t i = 0; i < BLOCK_SIZE; i++) {
        if (i >= 148 && i < 156) {
            sum += ' ';
        } else {
            sum += header[i];
        }
    }
    return sum;
}

static unsigned long long parse_tar_size(const unsigned char *header) {
    unsigned long long size = 0;
    size_t i = 124;
    while (i < 136 && (header[i] == ' ' || header[i] == '0')) i++;
    while (i < 136 && header[i] >= '0' && header[i] <= '7') {
        size = (size << 3) | (unsigned long long)(header[i] - '0');
        i++;
    }
    return size;
}

static void patch_file_in_place(const char *filepath) {
    struct stat st;
    if (lstat(filepath, &st) != 0) return;
    if (!S_ISREG(st.st_mode)) return;
    if (st.st_size <= 0) return;

    int fd = open(filepath, O_RDWR);
    if (fd < 0) return;

    unsigned char *buf = (unsigned char *)malloc((size_t)st.st_size);
    if (!buf) {
        close(fd);
        return;
    }

    ssize_t n = read_exact(fd, buf, (size_t)st.st_size);
    if (n == st.st_size) {
        int count = replace_bytes(buf, (size_t)st.st_size, OLD_ID, NEW_ID, ID_LEN);
        if (count > 0) {
            if (lseek(fd, 0, SEEK_SET) == 0) {
                write_exact(fd, buf, (size_t)st.st_size);
            }
        }
    }
    free(buf);
    close(fd);
}

static void scan_and_patch_dir(const char *dirpath) {
    DIR *d = opendir(dirpath);
    if (!d) return;

    struct dirent *ent;
    char path_buf[PATH_MAX];
    while ((ent = readdir(d)) != NULL) {
        if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0) continue;
        int ret = snprintf(path_buf, sizeof(path_buf), "%s/%s", dirpath, ent->d_name);
        if (ret <= 0 || (size_t)ret >= sizeof(path_buf)) continue;

        struct stat st;
        if (lstat(path_buf, &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) {
            scan_and_patch_dir(path_buf);
        } else if (S_ISREG(st.st_mode)) {
            patch_file_in_place(path_buf);
        }
    }
    closedir(d);
}

static int handle_control(int argc, char **argv) {
    pid_t pid = fork();
    if (pid < 0) {
        perror("fork failed in handle_control");
        return 1;
    }

    if (pid == 0) {
        const char *real_bin = get_real_dpkg_deb();
        execv(real_bin, argv);
        perror("execv dpkg-deb.real failed in handle_control");
        _exit(127);
    }

    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno == EINTR) continue;
        perror("waitpid failed in handle_control");
        return 1;
    }

    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        return WIFEXITED(status) ? WEXITSTATUS(status) : 1;
    }

    /* Target directory identification */
    const char *target_dir = NULL;
    if (argc >= 4) {
        struct stat st;
        if (stat(argv[argc - 1], &st) == 0 && S_ISDIR(st.st_mode)) {
            target_dir = argv[argc - 1];
        }
    }

    if (!target_dir) {
        struct stat st;
        if (stat("DEBIAN", &st) == 0 && S_ISDIR(st.st_mode)) {
            target_dir = "DEBIAN";
        }
    }

    if (target_dir) {
        scan_and_patch_dir(target_dir);
    }

    return 0;
}

static int handle_fsys_tarfile(char **argv) {
    int pfd[2];
    if (pipe(pfd) < 0) {
        perror("pipe failed in handle_fsys_tarfile");
        return 1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        perror("fork failed in handle_fsys_tarfile");
        close(pfd[0]);
        close(pfd[1]);
        return 1;
    }

    if (pid == 0) {
        close(pfd[0]);
        if (dup2(pfd[1], STDOUT_FILENO) < 0) {
            perror("dup2 failed in child");
            _exit(127);
        }
        close(pfd[1]);

        const char *real_bin = get_real_dpkg_deb();
        execv(real_bin, argv);
        perror("execv dpkg-deb.real failed in handle_fsys_tarfile");
        _exit(127);
    }

    close(pfd[1]);
    signal(SIGPIPE, SIG_IGN);

    unsigned char block[BLOCK_SIZE];
    int write_failed = 0;

    while (1) {
        ssize_t n = read_exact(pfd[0], block, BLOCK_SIZE);
        if (n <= 0) break;
        if ((size_t)n < BLOCK_SIZE) {
            if (write_exact(STDOUT_FILENO, block, (size_t)n) < 0) {
                write_failed = 1;
            }
            break;
        }

        int all_zero = 1;
        for (size_t i = 0; i < BLOCK_SIZE; i++) {
            if (block[i] != 0) {
                all_zero = 0;
                break;
            }
        }

        if (all_zero) {
            if (write_exact(STDOUT_FILENO, block, BLOCK_SIZE) < 0) {
                write_failed = 1;
                break;
            }
            continue;
        }

        /* Valid header block */
        unsigned long long size = parse_tar_size(block);
        int modified = replace_bytes(block, BLOCK_SIZE, OLD_ID, NEW_ID, ID_LEN);
        if (modified) {
            unsigned int chksum = calculate_tar_checksum(block);
            snprintf((char *)&block[148], 7, "%06o", chksum);
            block[154] = '\0';
            block[155] = ' ';
        }

        if (write_exact(STDOUT_FILENO, block, BLOCK_SIZE) < 0) {
            write_failed = 1;
            break;
        }

        /* Stream payload blocks */
        if (size > 0) {
            unsigned long long blocks = (size + BLOCK_SIZE - 1) / BLOCK_SIZE;
            size_t payload_bytes = (size_t)(blocks * BLOCK_SIZE);

            /* Allocate if <= 16MB for seamless 10-byte boundary handling */
            if (blocks <= (16 * 1024 * 1024 / BLOCK_SIZE)) {
                unsigned char *pbuf = (unsigned char *)malloc(payload_bytes);
                if (pbuf) {
                    ssize_t pn = read_exact(pfd[0], pbuf, payload_bytes);
                    if (pn > 0) {
                        replace_bytes(pbuf, (size_t)pn, OLD_ID, NEW_ID, ID_LEN);
                        if (write_exact(STDOUT_FILENO, pbuf, (size_t)pn) < 0) {
                            write_failed = 1;
                        }
                    }
                    free(pbuf);
                    if (write_failed || pn < (ssize_t)payload_bytes) break;
                    continue;
                }
            }

            /* Fallback block-by-block if large */
            for (unsigned long long b = 0; b < blocks; b++) {
                ssize_t pn = read_exact(pfd[0], block, BLOCK_SIZE);
                if (pn <= 0) {
                    write_failed = 1;
                    break;
                }
                replace_bytes(block, (size_t)pn, OLD_ID, NEW_ID, ID_LEN);
                if (write_exact(STDOUT_FILENO, block, (size_t)pn) < 0) {
                    write_failed = 1;
                    break;
                }
            }
            if (write_failed) break;
        }
    }

    close(pfd[0]);

    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno == EINTR) continue;
        break;
    }

    if (write_failed) return 0;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return 1;
}

int main(int argc, char **argv) {
    int is_fsys = 0;
    int is_ctrl = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--fsys-tarfile") == 0) {
            is_fsys = 1;
            break;
        }
        if (strcmp(argv[i], "--control") == 0 || strcmp(argv[i], "-e") == 0 ||
            strncmp(argv[i], "--control=", 10) == 0) {
            is_ctrl = 1;
            break;
        }
    }

    if (is_fsys) {
        return handle_fsys_tarfile(argv);
    }

    if (is_ctrl) {
        return handle_control(argc, argv);
    }

    const char *real_bin = get_real_dpkg_deb();
    execv(real_bin, argv);
    perror("execv dpkg-deb.real passthrough failed");
    return 1;
}
