from androguard.core.apk import APK
a = APK('honor-health.apk')
print("pkg:", a.get_package())
# services matching deviceauth
for s in a.get_services():
    if 'deviceauth' in s.lower() or 'DeviceAuth' in s:
        print("SERVICE:", s)
# permissions defined
print("--- custom permissions ---")
for p in a.get_declared_permissions():
    print("  ", p)
