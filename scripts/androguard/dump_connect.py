from androguard.core.dex import DEX
d = DEX(open('dexout/classes23.dex','rb').read())
for c in d.get_classes():
    if c.get_name() == 'Lcom/hihonor/deviceauthengine/HnDeviceAuthManager;':
        for m in c.get_methods():
            if m.get_name() in ('connectDeviceAuthService','getInstance','bindPeer','processReceivedData'):
                print("\n--- METHOD", m.get_name(), m.get_descriptor())
                if not m.get_code(): print("  (no code)"); continue
                for ins in m.get_instructions():
                    o=ins.get_output()
                    if ins.get_name().startswith(('invoke','const-string','new-instance','iget','sget','iput')):
                        print("   ", ins.get_name(), o)
