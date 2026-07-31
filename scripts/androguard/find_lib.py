from androguard.core.dex import DEX
d = DEX(open('dexout/classes23.dex','rb').read())
libs=set(); natives=[]
for c in d.get_classes():
    if 'deviceauthengine' not in c.get_name(): continue
    for m in c.get_methods():
        if not m.get_code(): 
            # possibly native
            if 'native' in (m.get_access_flags_string() or ''):
                natives.append(c.get_name()+"->"+m.get_name()+m.get_descriptor())
            continue
        prev=None
        for ins in m.get_instructions():
            o=ins.get_output()
            if 'loadLibrary' in o and prev is not None:
                libs.add(prev)
            if ins.get_name().startswith('const-string'):
                prev=o.split(',',1)[-1].strip()
print("loadLibrary args:", libs)
print("native methods (first 30):")
for n in natives[:30]: print("  ", n)
