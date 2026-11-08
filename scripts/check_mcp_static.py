#!/usr/bin/env python3
"""Consistency checks for the MCP feature that do not require a Kotlin compiler.

The authoritative check is the Gradle build in GitHub Actions. This script only guards
relationships that span files and that a compiler error would report confusingly:

  1. every KDoc/comment reference to a resource exists (@string, @drawable, R.string.*);
  2. every view id used through the generated ViewBinding exists in the layout;
  3. all declared tools are registered exactly once, and their names are unique;
  4. no file in feature/mcp references an Android API that would break the JVM unit tests
     (the protocol and transport packages must stay Android-free);
  5. string resources added for MCP exist in every locale that the app ships.

Exit code 1 when a problem is found.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main"
SRC = APP / "java/me/simpleHook/feature/mcp"
RES = APP / "res"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def strings_of(path: Path) -> set[str]:
    return set(re.findall(r'<string name="([^"]+)"', read(path)))


def main() -> int:
    problems: list[str] = []

    # 1. resource references from the new layout, screen and settings page.
    default_strings = strings_of(RES / "values/strings.xml")
    drawables = {p.stem for p in (RES / "drawable").glob("*.xml")}
    drawables |= {p.stem for p in (RES / "drawable").glob("*.png")}

    for layout in [RES / "layout/activity_mcp_settings.xml", RES / "xml/settings_preferences.xml"]:
        text = read(layout)
        for ref in re.findall(r"@string/([\w.]+)", text):
            if ref != "appbar_scrolling_view_behavior" and ref not in default_strings:
                problems.append(f"{layout.name}: missing @string/{ref}")
        for ref in re.findall(r"@drawable/([\w.]+)", text):
            if ref not in drawables:
                problems.append(f"{layout.name}: missing @drawable/{ref}")

    for kt in sorted(SRC.rglob("*.kt")):
        for ref in re.findall(r"R\.string\.(\w+)", read(kt)):
            if ref not in default_strings:
                problems.append(f"{kt.relative_to(ROOT)}: missing R.string.{ref}")

    # 2. ViewBinding ids used by the settings screen.
    activity = SRC / "ui/McpSettingsActivity.kt"
    layout_ids = set(re.findall(
        r'android:id="@\+id/(\w+)"', read(RES / "layout/activity_mcp_settings.xml")))
    for used in set(re.findall(r"binding\.(\w+)", read(activity))):
        if used == "root" or used[:1].isupper():
            continue
        if used not in layout_ids:
            problems.append(f"McpSettingsActivity: binding.{used} has no view in the layout")

    # 3. Tools registered in the factory must exist as classes and be unique.
    factory = read(SRC / "McpServerFactory.kt")
    registered = re.findall(r"(\w+Tool)\(data\)", factory)
    duplicates = {name for name in registered if registered.count(name) > 1}
    if duplicates:
        problems.append(f"McpServerFactory registers a tool twice: {sorted(duplicates)}")
    tool_classes = {
        m.group(1)
        for p in (SRC / "tools").glob("*.kt")
        for m in re.finditer(r"^class (\w+Tool)\b", read(p), re.M)
    }
    for name in registered:
        if name not in tool_classes:
            problems.append(f"McpServerFactory references unknown tool class {name}")
    for name in sorted(tool_classes - set(registered)):
        problems.append(f"tool class {name} is never registered")

    tool_names = []
    for p in (SRC / "tools").glob("*.kt"):
        tool_names += re.findall(r'override val name: String = "([\w.-]+)"', read(p))
    if len(tool_names) != len(set(tool_names)):
        problems.append(f"duplicate tool names: {sorted(tool_names)}")

    # 4. The protocol and transport packages must stay free of Android imports so the JVM
    #    unit tests can run them without Robolectric or an emulator.
    for package in ["protocol", "tools"]:
        for kt in (SRC / package).glob("*.kt"):
            text = read(kt)
            if re.search(r"^import android", text, re.M) or "R." in text:
                problems.append(
                    f"{kt.relative_to(ROOT)}: must not depend on Android APIs "
                    "(it is covered by JVM unit tests)"
                )

    # 5. Every protocol symbol used inside feature/mcp must be imported or declared locally.
    protocol_names = set()
    for kt in SRC.rglob("*.kt"):
        protocol_names |= set(re.findall(
            r"^(?:internal |private )?(?:sealed |data |abstract |open )*"
            r"(?:class|interface|object|enum class) (\w+)", read(kt), re.M))
        protocol_names |= set(re.findall(r"^(?:internal )?fun (\w+)", read(kt), re.M))
        protocol_names |= set(re.findall(r"^(?:internal )?(?:const )?val (\w+)", read(kt), re.M))
    used_symbol = re.compile(r"(?<![\w.])(%s)\b" % "|".join(sorted(protocol_names)))

    # Symbols are visible without an import when they live in the same package.
    package_symbols: dict[str, set[str]] = {}
    for kt in SRC.rglob("*.kt"):
        text = read(kt)
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        if not pkg:
            continue
        names = set(re.findall(
            r"^(?:internal |private )?(?:sealed |data |abstract |open )*"
            r"(?:class|interface|object|enum class) (\w+)", text, re.M))
        names |= set(re.findall(r"^(?:internal )?(?:fun|val|const val) (\w+)", text, re.M))
        package_symbols.setdefault(pkg.group(1), set()).update(names)

    for kt in sorted(SRC.rglob("*.kt")):
        text = read(kt)
        pkg_match = re.search(r"^package\s+([\w.]+)", text, re.M)
        same_package = package_symbols.get(pkg_match.group(1), set()) if pkg_match else set()
        imported = {m.group(1) for m in
                    re.finditer(r"^import [\w.]+\.(\w+)(?:\s+as\s+\w+)?$", text, re.M)}
        declared_here = set(re.findall(
            r"^(?:internal |private )?(?:sealed |data |abstract |open )*"
            r"(?:class|interface|object|enum class) (\w+)", text, re.M))
        body = re.sub(r"^import .*$", "", text, flags=re.M)
        for match in used_symbol.finditer(body):
            name = match.group(1)
            if name in imported or name in declared_here or name in same_package:
                continue
            problems.append(
                f"{kt.relative_to(ROOT)}: '{name}' is used but not imported "
                "(missing import will fail the build)"
            )

    # 6. The foreground-service type must be one the app can actually start.
    #    Types in RESTRICTED_TYPES require an extra runtime permission (or user action) that a
    #    loopback-only MCP server must not request; `connectedDevice` for example additionally
    #    needs BLUETOOTH_CONNECT, CHANGE_NETWORK_STATE, CHANGE_WIFI_STATE, NFC, ... from
    #    Android 14 on, and starting it without them throws SecurityException at runtime.
    RESTRICTED_TYPES = {
        "connectedDevice": "BLUETOOTH_*/CHANGE_NETWORK_STATE/CHANGE_WIFI_STATE/NFC",
        "camera": "CAMERA",
        "microphone": "RECORD_AUDIO",
        "location": "ACCESS_FINE_LOCATION",
        "phoneCall": "MANAGE_OWN_CALLS",
        "mediaPlayback": "FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        "mediaProjection": "screen capture consent",
        "health": "ACTIVITY_RECOGNITION/BODY_SENSORS",
        "shortService": "short-lived work only",
    }
    manifest = read(APP / "AndroidManifest.xml")
    service_block = re.search(
        r'<service\s+[^>]*android:name="\.feature\.mcp\.service\.McpService"[\s\S]*?</service>',
        manifest)
    if not service_block:
        problems.append("AndroidManifest.xml: McpService declaration not found")
    else:
        block = service_block.group(0)
        type_match = re.search(r'android:foregroundServiceType="([^"]+)"', block)
        if not type_match:
            problems.append("AndroidManifest.xml: McpService has no foregroundServiceType")
        else:
            declared = {t.strip() for t in type_match.group(1).split("|")}
            for name in sorted(declared):
                if name in RESTRICTED_TYPES:
                    problems.append(
                        f"AndroidManifest.xml: McpService uses '{name}', which needs "
                        f"{RESTRICTED_TYPES[name]}; the MCP server must use specialUse"
                    )
            if "specialUse" not in declared:
                problems.append("AndroidManifest.xml: McpService must use specialUse")
            if "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" not in block:
                problems.append(
                    "AndroidManifest.xml: specialUse requires a "
                    "PROPERTY_SPECIAL_USE_FGS_SUBTYPE property"
                )
        for perm in ["android.permission.FOREGROUND_SERVICE",
                     "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"]:
            if perm not in manifest:
                problems.append(f"AndroidManifest.xml: missing {perm}")

    # The runtime startForeground() type must match the manifest declaration.
    service_src = read(SRC / "service/McpService.kt")
    if "FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE" in service_src:
        problems.append("McpService.kt: startForeground still uses CONNECTED_DEVICE")
    if "FOREGROUND_SERVICE_TYPE_SPECIAL_USE" not in service_src:
        problems.append("McpService.kt: startForeground must pass SPECIAL_USE on API 34+")

    # 7. Keep-alive screen: resources, binding ids, manifest entry, locale parity.
    keepalive_layout = RES / "layout/activity_keep_alive.xml"
    if not keepalive_layout.exists():
        problems.append("layout/activity_keep_alive.xml is missing")
    else:
        layout_text = read(keepalive_layout)
        ka_ids = set(re.findall(r'android:id="@\+id/(\w+)"', layout_text))
        ka_activity = APP / "java/me/simpleHook/feature/keepalive/KeepAliveActivity.kt"
        if not ka_activity.exists():
            problems.append("KeepAliveActivity.kt is missing")
        else:
            ka_src = read(ka_activity)
            for used in set(re.findall(r"binding\.(\w+)", ka_src)):
                if used == "root" or used[:1].isupper():
                    continue
                if used not in ka_ids:
                    problems.append(f"KeepAliveActivity: binding.{used} has no view in the layout")
            for ref in set(re.findall(r"R\.string\.(\w+)", ka_src)):
                if ref not in default_strings:
                    problems.append(f"KeepAliveActivity: missing R.string.{ref}")
        for ref in re.findall(r"@string/([\w.]+)", layout_text):
            if ref != "appbar_scrolling_view_behavior" and ref not in default_strings:
                problems.append(f"activity_keep_alive.xml: missing @string/{ref}")
        for ref in re.findall(r"@drawable/([\w.]+)", layout_text):
            if ref not in drawables:
                problems.append(f"activity_keep_alive.xml: missing @drawable/{ref}")

    # Manifest: the screen must exist, must not be exported, and the battery-exemption
    # permission must be declared or the request dialog can never be shown.
    if 'android:name=".feature.keepalive.KeepAliveActivity"' not in manifest:
        problems.append("AndroidManifest.xml: KeepAliveActivity is not declared")
    if "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" not in manifest:
        problems.append("AndroidManifest.xml: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS missing")
    if "android.permission.POST_NOTIFICATIONS" not in manifest:
        problems.append("AndroidManifest.xml: POST_NOTIFICATIONS missing")

    # Keep-alive locale parity.
    ka_keys = {k for k in default_strings if k.startswith(("keepalive_", "main_settings_title_keepalive",
                                                           "main_settings_summary_keepalive"))}
    for locale in ["values-en", "values-zh-rTW"]:
        locale_strings = strings_of(RES / f"{locale}/strings.xml")
        missing = sorted(ka_keys - locale_strings)
        if missing:
            problems.append(f"{locale}/strings.xml: missing {len(missing)} keep-alive string(s): {missing}")

    # 8. Locales must stay in sync for the keys this feature adds.
    mcp_keys = {k for k in default_strings if k.startswith(("mcp_", "main_settings_title_mcp",
                                                           "main_settings_summary_mcp"))}
    for locale in ["values-en", "values-zh-rTW"]:
        locale_strings = strings_of(RES / f"{locale}/strings.xml")
        missing = sorted(mcp_keys - locale_strings)
        if missing:
            problems.append(f"{locale}/strings.xml: missing {len(missing)} MCP string(s): {missing}")

    if problems:
        print("\n".join(sorted(set(problems))))
        print(f"\n{len(set(problems))} problem(s)")
        return 1
    print(f"static checks passed: {len(tool_names)} tools, {len(mcp_keys)} MCP strings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
