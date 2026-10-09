#!/usr/bin/env python3
"""Drive the app on a connected emulator/device for visual checks.

Usage: scripts/ui.py <command> [args] [-- <command> [args] ...]

  launch             (re)start the app from a cold start
  tap <label>        tap the first element whose text or content description contains <label>
  tapxy <x> <y>      tap raw screen coordinates
  type <text>        type into the focused field (ASCII only)
  key <name>         send a key event: back, enter, tab, or any KEYCODE_* name
  swipe <up|down>    scroll the screen one step
  shot <file.png>    save a screenshot
  dump               print visible texts and content descriptions
  wait <seconds>     pause

Commands run in order, so one call can script a whole flow.
Requires `adb` on PATH (or ANDROID_HOME set).
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = "adb"
PACKAGE = "com.northphoenix.hairdresserclientmanager"
if os.environ.get("ANDROID_HOME"):
    candidate = os.path.join(os.environ["ANDROID_HOME"], "platform-tools", "adb")
    if os.path.exists(candidate):
        ADB = candidate


def adb(*args, binary=False):
    result = subprocess.run([ADB, *args], capture_output=True, check=True)
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def tappable(node, parents):
    while node is not None:
        if node.get("clickable") == "true":
            return True
        node = parents.get(node)
    return False


def tree():
    # uiautomator can fail while the UI is animating, so retry briefly.
    for _ in range(5):
        xml = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        start = xml.find("<?xml")
        end = xml.rfind("</hierarchy>")
        if start != -1 and end != -1:
            return ET.fromstring(xml[start : end + len("</hierarchy>")])
        time.sleep(0.5)
    raise SystemExit("could not dump the UI hierarchy")


def nodes():
    return list(tree().iter("node"))


def center(node):
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
    return (left + right) // 2, (top + bottom) // 2


def tap(label):
    root = tree()
    parents = {child: parent for parent in root.iter() for child in parent}
    matches = [n for n in root.iter("node") if label.lower() in (n.get("text", "") + "\n" + n.get("content-desc", "")).lower()]
    if not matches:
        raise SystemExit(f"no element matching {label!r}")
    exact = [n for n in matches if label.lower() in (n.get("text", "").lower(), n.get("content-desc", "").lower())]
    candidates = exact or matches
    # A button and a field label can share the same words; the tappable one is what was meant.
    clickable = [n for n in candidates if tappable(n, parents)]
    x, y = center((clickable or candidates)[0])
    adb("shell", "input", "tap", str(x), str(y))


KEYS = {"back": "KEYCODE_BACK", "enter": "KEYCODE_ENTER", "tab": "KEYCODE_TAB"}


def run(command, args):
    if command == "launch":
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        time.sleep(4)
    elif command == "tap":
        tap(args[0])
    elif command == "tapxy":
        adb("shell", "input", "tap", args[0], args[1])
    elif command == "type":
        adb("shell", "input", "text", args[0].replace(" ", "%s"))
    elif command == "key":
        adb("shell", "input", "keyevent", KEYS.get(args[0], args[0]))
    elif command == "swipe":
        start, end = ("1700", "700") if args[0] == "up" else ("700", "1700")
        adb("shell", "input", "swipe", "540", start, "540", end, "300")
    elif command == "shot":
        with open(args[0], "wb") as output:
            output.write(adb("exec-out", "screencap", "-p", binary=True))
    elif command == "dump":
        for node in nodes():
            label = " | ".join(filter(None, [node.get("text"), node.get("content-desc")]))
            if label:
                print(label)
    elif command == "wait":
        time.sleep(float(args[0]))
    else:
        raise SystemExit(f"unknown command {command!r}\n{__doc__}")
    time.sleep(0.6)


def main(argv):
    if not argv:
        raise SystemExit(__doc__)
    step = []
    for token in argv + ["--"]:
        if token == "--":
            if step:
                run(step[0], step[1:])
            step = []
        else:
            step.append(token)


if __name__ == "__main__":
    main(sys.argv[1:])
