from androguard.core.dex import DEX
d = DEX(open('dexout/classes2.dex','rb').read())
for c in d.get_classes():
    if c.get_name() == 'Lcom/hihonor/hwddmp/connection/bluetooth/mbb/MbbHandShakeCmd;':
        for m in c.get_methods():
            if m.get_name() in ('a','b'):
                print("--- METHOD", m.get_name(), m.get_descriptor())
                for ins in m.get_instructions():
                    print("   ", ins.get_name(), ins.get_output())
                print()
