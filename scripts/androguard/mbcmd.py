import logging; logging.getLogger('androguard').setLevel(logging.ERROR)
from androguard.core.dex import DEX
dx = DEX(open("dexout/classes2.dex","rb").read())
want = {"Lcom/hihonor/hwddmp/connection/bluetooth/mbb/MbbHandShakeCmd;",
        "Lcom/hihonor/hwddmp/connection/bluetooth/mbb/MbbCommand;",
        "Lcom/hihonor/hwddmp/connection/bluetooth/mbb/MbbHiChainResponse;"}
for c in dx.get_classes():
    if c.get_name() in want:
        print("\n########## CLASS", c.get_name(), "fields:", [f.get_name() for f in c.get_fields()])
        for m in c.get_methods():
            nm=m.get_name()
            if nm in ('<clinit>',): continue
            # dump all methods but focus on pack*/decode/get*
            print("\n--- METHOD", nm, m.get_descriptor())
            try:
                for ins in m.get_instructions():
                    print("   ", ins.get_name(), ins.get_operands())
            except Exception as e:
                print("   (no ins)", e)
