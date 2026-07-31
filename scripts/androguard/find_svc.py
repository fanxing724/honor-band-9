import glob
from androguard.core.dex import DEX
targets = ['Lcom/hihonor/deviceauthengine/HnDeviceAuthService;',
           'Lcom/hihonor/deviceauthengine/IHichainService;']
for path in sorted(glob.glob('dexout/*.dex')):
    try: d = DEX(open(path,'rb').read())
    except: continue
    for c in d.get_classes():
        nm=c.get_name()
        if nm in targets or ('deviceauthengine' in nm and 'Service' in nm):
            print(path, nm)
