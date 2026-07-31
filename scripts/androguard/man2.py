from androguard.core.apk import APK
from lxml import etree
a = APK('honor-health.apk')
axml = a.get_android_manifest_xml()
ns='{http://schemas.android.com/apk/res/android}'
for s in axml.iter('service'):
    nm=s.get(ns+'name') or ''
    if 'HnDeviceAuthService' in nm:
        print("SERVICE", nm)
        for k,v in s.attrib.items():
            print("   attr", k.replace(ns,''), "=", v)
        for ch in s:
            print("   child", ch.tag, dict((k.replace(ns,''),v) for k,v in ch.attrib.items()))
            for gc in ch:
                print("      ", gc.tag, dict((k.replace(ns,''),v) for k,v in gc.attrib.items()))
