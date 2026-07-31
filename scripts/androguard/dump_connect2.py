from androguard.core.dex import DEX
d = DEX(open('dexout/classes23.dex','rb').read())
for c in d.get_classes():
    if c.get_name() == 'Lcom/hihonor/deviceauthengine/HnDeviceAuthManager;':
        for m in c.get_methods():
            if m.get_name() in ('connectDeviceAuthService','<init>','createSessionInfo'):
                print("\n--- METHOD", m.get_name(), m.get_descriptor())
                if not m.get_code(): continue
                for ins in m.get_instructions():
                    o=ins.get_output()
                    if any(k in o for k in ('const-class','setPackage','setComponent','setAction','ComponentName','Intent;-><init>','.aidl','Service','packageName','PackageName','const-string')) :
                        print("   ", ins.get_name(), o)
