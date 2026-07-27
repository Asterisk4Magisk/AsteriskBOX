# setuidgid

`setuidgid` drops supplementary groups, real/effective/saved GID, and real/effective/saved UID before replacing itself with another program. It is intended for a privileged Android/Linux parent process that needs to launch a child under an explicit numeric identity.

The source root contains one production file. A parent Android project compiles it as a PIE executable, commonly packaged as `libsetuidgid.so`.

## Command line

```text
setuidgid UID PROGRAM [ARG...]
setuidgid UID GID PROGRAM [ARG...]
```

`UID` and optional `GID` are decimal integers from 0 through 2147483647. When GID is omitted, it defaults to UID. A numeric second argument is always interpreted as GID; use an explicit GID when the program name itself is numeric.

Example:

```text
setuidgid 1000 3003 /system/bin/id
setuidgid 2000 /system/bin/sh -c "exec /data/local/tmp/example/program --config /data/local/tmp/example/config.json"
```

The operation order is:

1. `setgroups(0, NULL)` removes all supplementary groups.
2. `setresgid(gid, gid, gid)` sets real, effective, and saved GID.
3. `setresuid(uid, uid, uid)` sets real, effective, and saved UID.
4. `execvp(PROGRAM, argv)` replaces `setuidgid` and forwards all remaining arguments.

Invalid arguments, identity-change failures, and `execvp` failures print an error and exit with status `1`. A successful call does not return; the final exit status is the launched program's status.

## Platform requirements and safety

The caller needs privileges sufficient for `setgroups`, `setresgid`, and `setresuid` (normally root). Group removal happens before dropping GID/UID so the child cannot retain unexpected supplementary access. Treat the program path and arguments as trusted input; `execvp` performs PATH lookup for names without `/`.

## Parent-project integration

Mount the repository root directly at `setuidgid/src/main/native`. Keep Android ABI selection, NDK flags, executable packaging, and Gradle tasks in the parent project.

## License

GPL-3.0. See [LICENSE](LICENSE).
