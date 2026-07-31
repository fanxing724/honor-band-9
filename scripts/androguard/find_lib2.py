import glob
from androguard.core.dex import DEX
# search all dex for loadLibrary strings + hichain-related native usage
hits=set()
for path in sorted(glob.glob('dexout/*.dex')):
    try: d=DEX(open(path,'rb').read())
    except: continue
    for c in d.get_classes():
        nm=c.get_name()
        if not any(k in nm for k in ('deviceauthengine','hichain','Hichain','HiChain','deviceauth')): continue
        for m in c.get_methods():
            if not m.get_code(): continue
            prev=None
            for ins in m.get_instructions():
                o=ins.get_output()
                if 'System;->loadLibrary' in o or 'loadLibrary' in o:
                    hits.add((path,nm,m.get_name(),prev))
                if ins.get_name().startswith('const-string'):
                    prev=o
print("loadLibrary sites:")
for h in hits: print("  ",h)
