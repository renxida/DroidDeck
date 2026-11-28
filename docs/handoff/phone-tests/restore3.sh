if pgrep -f 'sts2|SlayTheSpire|wine' >/dev/null; then echo GAME-RUNNING-NOTHING-DONE; pgrep -af 'sts2|wine' | head -3; exit 0; fi
python3 /tmp/lsh/protonref.py off 2>&1 | cut -c1-120
python3 - <<'P'
import os, shutil
d="/root/.cache/droiddeck/native/native-engine-95"
if os.path.isdir(d+".orig"):
    if any(f.startswith(".lanner-test-") for f in os.listdir(d)): shutil.rmtree(d)
    os.rename(d+".orig", d); print("restored95", sorted(f for f in os.listdir(d) if f.startswith('.')))
else: print("no-orig", sorted(f for f in os.listdir(d) if f.startswith('.')))
P
B=$(ls -d /root/lanner-save-backup-test-* | sort | tail -1); echo using $B
U=/root/.local/share/Steam/userdata/155889176/2868840; G=/root/.local/share/SlayTheSpire2/steam
if [ -d $B/steam-2868840 ] && [ -d $B/game-steam ]; then rm -rf $U $G; cp -a $B/steam-2868840 $U; cp -a $B/game-steam $G; echo saves-restored; else echo NO-BACKUP; ls $B; fi
find $G -name progress.save -exec sha256sum {} \; | cut -c1-8
rm -f /tmp/lsh/config.json "/root/.local/share/Steam/steamapps/common/Slay the Spire 2/override.cfg"
ls /root/.cache/droiddeck/native/check/; ls /root/.cache/droiddeck/native/
