from androguard.core.dex import DEX
d = DEX(open('dexout/classes2.dex','rb').read())
targets = ['Ln35;', 'Lm35;', 'Ll35;',
           'Lcom/hihonor/hwddmp/connection/bluetooth/mbb/MbbHiChainResponse;',
           'Lcom/hihonor/hwddmp/connection/bluetooth/mbb/MbbAuthTypeResponse;']
for c in d.get_classes():
    nm = c.get_name()
    if nm in targets:
        print("\n===== CLASS", nm, "=====")
        for m in c.get_methods():
            print("--- METHOD", m.get_name(), m.get_descriptor())
            try:
                for ins in m.get_instructions():
                    print("   ", ins.get_name(), ins.get_output())
            except Exception as e:
                print("   (no code)", e)
