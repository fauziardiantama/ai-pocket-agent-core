#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <limits.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <stddef.h>
#include <stdbool.h>

#define SOCKET_ABSTRACT_NAME "ai.pa.core.paservice"
#define BUFFER_SIZE 65536

static void print_usage(const char *prog) {
    fprintf(stderr, "Usage: %s <command> [options]\n", prog);
    fprintf(stderr, "Commands:\n");
    fprintf(stderr, "  start <name> [--autostart] [--] <cmd> [args...]\n");
    fprintf(stderr, "  stop <name>\n");
    fprintf(stderr, "  status [name]\n");
    fprintf(stderr, "  restart <name>\n");
}

static bool append_raw(char *buf, size_t cap, size_t *pos, const char *str) {
    size_t len = strlen(str);
    if (*pos + len >= cap) {
        return false;
    }
    memcpy(buf + *pos, str, len);
    *pos += len;
    buf[*pos] = '\0';
    return true;
}

static bool append_json_escaped(char *buf, size_t cap, size_t *pos, const char *str) {
    if (!append_raw(buf, cap, pos, "\"")) return false;
    for (size_t i = 0; str[i] != '\0'; i++) {
        unsigned char c = (unsigned char)str[i];
        if (c == '"') {
            if (!append_raw(buf, cap, pos, "\\\"")) return false;
        } else if (c == '\\') {
            if (!append_raw(buf, cap, pos, "\\\\")) return false;
        } else if (c == '\b') {
            if (!append_raw(buf, cap, pos, "\\b")) return false;
        } else if (c == '\f') {
            if (!append_raw(buf, cap, pos, "\\f")) return false;
        } else if (c == '\n') {
            if (!append_raw(buf, cap, pos, "\\n")) return false;
        } else if (c == '\r') {
            if (!append_raw(buf, cap, pos, "\\r")) return false;
        } else if (c == '\t') {
            if (!append_raw(buf, cap, pos, "\\t")) return false;
        } else if (c < 0x20) {
            char hex[7];
            snprintf(hex, sizeof(hex), "\\u%04x", c);
            if (!append_raw(buf, cap, pos, hex)) return false;
        } else {
            if (*pos + 1 >= cap) return false;
            buf[*pos] = (char)c;
            (*pos)++;
            buf[*pos] = '\0';
        }
    }
    if (!append_raw(buf, cap, pos, "\"")) return false;
    return true;
}

int main(int argc, char *argv[]) {
    if (argc < 2) {
        print_usage(argv[0]);
        return 1;
    }

    const char *action = argv[1];
    char json_buf[BUFFER_SIZE];
    json_buf[0] = '\0';
    size_t pos = 0;
    bool ok = true;

    if (strcmp(action, "start") == 0) {
        if (argc < 4) {
            fprintf(stderr, "Error: start requires <name> and <cmd>\n");
            print_usage(argv[0]);
            return 1;
        }
        const char *name = argv[2];
        bool autostart = false;
        int cmd_idx = 3;

        while (cmd_idx < argc) {
            if (strcmp(argv[cmd_idx], "--autostart") == 0) {
                autostart = true;
                cmd_idx++;
            } else if (strcmp(argv[cmd_idx], "--") == 0) {
                cmd_idx++;
                break;
            } else {
                break;
            }
        }

        if (cmd_idx >= argc) {
            fprintf(stderr, "Error: missing command after options\n");
            return 1;
        }

        const char *cmd = argv[cmd_idx];

        char cwd_buf[PATH_MAX];
        const char *cwd = getcwd(cwd_buf, sizeof(cwd_buf));
        if (cwd == NULL) {
            cwd = getenv("HOME");
            if (cwd == NULL) {
                cwd = "/";
            }
        }

        ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, "{\"action\":\"start\",\"name\":");
        ok = ok && append_json_escaped(json_buf, sizeof(json_buf), &pos, name);
        ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, ",\"cmd\":");
        ok = ok && append_json_escaped(json_buf, sizeof(json_buf), &pos, cmd);
        ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, ",\"cwd\":");
        ok = ok && append_json_escaped(json_buf, sizeof(json_buf), &pos, cwd);
        ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, ",\"args\":[");

        for (int i = cmd_idx + 1; i < argc; i++) {
            if (i > cmd_idx + 1) {
                ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, ",");
            }
            ok = ok && append_json_escaped(json_buf, sizeof(json_buf), &pos, argv[i]);
        }

        ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, "],\"autostart\":");
        ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, autostart ? "true" : "false");
        ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, "}\n");

    } else if (strcmp(action, "stop") == 0) {
        if (argc < 3) {
            fprintf(stderr, "Error: stop requires <name>\n");
            return 1;
        }
        ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, "{\"action\":\"stop\",\"name\":");
        ok = ok && append_json_escaped(json_buf, sizeof(json_buf), &pos, argv[2]);
        ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, "}\n");

    } else if (strcmp(action, "restart") == 0) {
        if (argc < 3) {
            fprintf(stderr, "Error: restart requires <name>\n");
            return 1;
        }
        ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, "{\"action\":\"restart\",\"name\":");
        ok = ok && append_json_escaped(json_buf, sizeof(json_buf), &pos, argv[2]);
        ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, "}\n");

    } else if (strcmp(action, "status") == 0) {
        if (argc >= 3) {
            ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, "{\"action\":\"status\",\"name\":");
            ok = ok && append_json_escaped(json_buf, sizeof(json_buf), &pos, argv[2]);
            ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, "}\n");
        } else {
            ok = ok && append_raw(json_buf, sizeof(json_buf), &pos, "{\"action\":\"status\"}\n");
        }
    } else {
        fprintf(stderr, "Unknown command: %s\n", action);
        print_usage(argv[0]);
        return 1;
    }

    if (!ok) {
        fprintf(stderr, "Error: command payload exceeds maximum buffer size (%d bytes)\n", BUFFER_SIZE);
        return 1;
    }

    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0) {
        perror("socket");
        return 1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    size_t name_len = strlen(SOCKET_ABSTRACT_NAME);
    if (name_len > sizeof(addr.sun_path) - 2) {
        name_len = sizeof(addr.sun_path) - 2;
    }
    memcpy(addr.sun_path + 1, SOCKET_ABSTRACT_NAME, name_len);

    socklen_t addr_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);

    if (connect(sock, (struct sockaddr *)&addr, addr_len) < 0) {
        fprintf(stderr, "Error: Cannot connect to pa-service supervisor (socket \\0%s).\n"
                        "Make sure 'Pocket Agent Core' is enabled in Android Accessibility Settings.\n",
                SOCKET_ABSTRACT_NAME);
        close(sock);
        return 1;
    }

    size_t total_sent = 0;
    size_t payload_len = strlen(json_buf);
    while (total_sent < payload_len) {
        ssize_t sent = write(sock, json_buf + total_sent, payload_len - total_sent);
        if (sent <= 0) {
            perror("write");
            close(sock);
            return 1;
        }
        total_sent += (size_t)sent;
    }

    char resp_buf[BUFFER_SIZE];
    ssize_t bytes_read = 0;
    size_t total_read = 0;
    while (total_read < sizeof(resp_buf) - 1 &&
           (bytes_read = read(sock, resp_buf + total_read, sizeof(resp_buf) - 1 - total_read)) > 0) {
        total_read += (size_t)bytes_read;
        if (resp_buf[total_read - 1] == '\n') break;
    }
    close(sock);

    if (bytes_read < 0) {
        perror("read");
        return 1;
    }

    resp_buf[total_read] = '\0';
    fputs(resp_buf, stdout);
    if (total_read > 0 && resp_buf[total_read - 1] != '\n') {
        putchar('\n');
    }

    if (strstr(resp_buf, "\"status\":\"ok\"") != NULL) {
        return 0;
    }

    return 1;
}
