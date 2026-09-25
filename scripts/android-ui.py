#!/usr/bin/env python3
"""Small adb UI helper for the two owned Android test devices. No app internals bypassed."""
import argparse, pathlib, re, subprocess, sys, time, xml.etree.ElementTree as ET

def adb(serial, *args):
    return subprocess.check_output([ADB, '-s', serial, *args], stderr=subprocess.STDOUT)

def snapshot(serial):
    adb(serial, 'shell', 'uiautomator', 'dump', '/sdcard/screenshare-ui.xml')
    return ET.fromstring(adb(serial, 'exec-out', 'cat', '/sdcard/screenshare-ui.xml'))

def texts(tree):
    return [n.attrib.get('text') or n.attrib.get('content-desc') for n in tree.iter('node') if n.attrib.get('text') or n.attrib.get('content-desc')]

def tap(serial, label):
    tree = snapshot(serial)
    for n in tree.iter('node'):
        if label in [n.attrib.get('text'), n.attrib.get('content-desc')]:
            points = list(map(int, re.findall(r'\d+', n.attrib['bounds'])))
            if len(points) == 4:
                adb(serial, 'shell', 'input', 'tap', str((points[0] + points[2]) // 2), str((points[1] + points[3]) // 2)); return
    raise SystemExit('Not found: '+label+'\n'+str(texts(tree)))

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('serial')
parser.add_argument('action', choices=['dump','tap','wait'])
parser.add_argument('text', nargs='?')
args=parser.parse_args();ADB=args.adb
if args.action=='dump': print('\n'.join(texts(snapshot(args.serial))))
elif args.action=='tap':tap(args.serial,args.text)
else:
    deadline=time.monotonic()+40
    while time.monotonic()<deadline:
        found=texts(snapshot(args.serial))
        if args.text in found:print('PASS:',args.text);sys.exit(0)
        time.sleep(1)
    raise SystemExit('Timed out: '+args.text+'\n'+str(found))
