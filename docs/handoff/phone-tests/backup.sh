if pgrep -f '[s]ts2\.|[S]layTheSpire2\.|[w]ine64|[w]ineserver' >/dev/null; then echo GAME-RUNNING-NOTHING-DONE; exit 0; fi
B=/root/lanner-save-backup-test-$(date -u +%Y%m%d-%H%M); U=/root/.local/share/Steam/userdata/155889176/2868840; G=/root/.local/share/SlayTheSpire2/steam
mkdir -p $B && cp -a $U $B/steam-2868840 && cp -a $G $B/game-steam && echo BACKUP $B
