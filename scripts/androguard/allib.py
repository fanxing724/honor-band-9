import glob
from androguard.core.dex import DEX
libs={}
for path in sorted(glob.glob('dexout/*.dex')):
    try: d=DEX(open(path,'rb').read())
    except: continue
    for c in d.get_classes():
        for m in c.get_methods():
            if not m.get_code(): continue
            prev=None
            for ins in m.get_instructions():
                o=ins.get_output()
                if 'loadLibrary' in o and prev:
                    libs.setdefault(prev.strip(),set()).add(c.get_name())
                if ins.get_name().startswith('const-string'):
                    prev=o.split(',',1)[-1]
for k in sorted(libs): print(k, "  loaders:", list(libs[k])[:3])
