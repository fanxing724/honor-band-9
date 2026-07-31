from androguard.core.dex import DEX
d = DEX(open('dexout/classes2.dex','rb').read())
want=('authDevice','connectHiChainAuthService','init','<init>','startAuth','onServiceConnected','setup','registerNewUser')
for c in d.get_classes():
    if 'MbbHiChainAuthManager' in c.get_name():
        print("CLASS", c.get_name())
        for m in c.get_methods():
            if m.get_name() in want:
                print("\n--- METHOD", m.get_name(), m.get_descriptor())
                if not m.get_code(): 
                    print("   (abstract)"); continue
                for ins in m.get_instructions():
                    o=ins.get_output()
                    if any(k in o for k in ('HnDeviceAuthManager','OperationParameter','UserInfo','WEAR','registerNewUser','connectDeviceAuthService','isTrustPeer','const-string')) or ins.get_name().startswith(('invoke','iput','sget')):
                        print("   ", ins.get_name(), o)
