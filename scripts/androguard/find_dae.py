import glob
from androguard.core.dex import DEX
for path in sorted(glob.glob('dexout/*.dex')):
    try:
        d = DEX(open(path,'rb').read())
    except Exception:
        continue
    for c in d.get_classes():
        if c.get_name() == 'Lcom/hihonor/deviceauthengine/HnDeviceAuthManager;':
            print("FOUND in", path)
            for m in c.get_methods():
                print("   METHOD", m.get_name(), m.get_descriptor())
