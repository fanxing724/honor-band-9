from androguard.core.dex import DEX
d = DEX(open('dexout/classes2.dex','rb').read())
for c in d.get_classes():
    if 'MbbHiChainAuthManager' in c.get_name():
        for m in c.get_methods():
            code = m.get_code()
            if not code: continue
            txt = []
            for ins in m.get_instructions():
                txt.append(ins.get_name()+" "+ins.get_output())
            body = "\n".join(txt)
            if 'resolveAuthData' in body or 'resolveAuthTypeResponse' in body or 'packAuthTypeRequest' in body or 'packHiChainData' in body:
                print("##### METHOD", m.get_name(), m.get_descriptor())
                for t in txt:
                    print("  ", t)
                print()
