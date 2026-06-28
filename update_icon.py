#!/usr/bin/env python3
import os
import re
import sys
import ssl
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET
from PIL import Image

# Standard Android mipmap resolutions
RESOLUTIONS = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192
}

def parse_start_url(manifest_path):
    """Extracts START_URL from the AndroidManifest.xml meta-data."""
    if not os.path.exists(manifest_path):
        print(f"Error: Manifest file not found at {manifest_path}")
        return None
        
    try:
        # Register namespaces to prevent namespace prefix issues in parsing
        ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
        ET.register_namespace("tools", "http://schemas.android.com/tools")
        
        tree = ET.parse(manifest_path)
        root = tree.getroot()
        
        # Search for the START_URL meta-data tag
        for meta in root.findall(".//meta-data"):
            name = meta.get("{http://schemas.android.com/apk/res/android}name")
            if name == "de.kalass.familyappifier.START_URL":
                return meta.get("{http://schemas.android.com/apk/res/android}value")
    except Exception as e:
        print(f"Warning: Failed to parse manifest XML ({e}). Attempting regex fallback.")
        
    # Regex fallback if XML parsing fails due to complex templates
    try:
        with open(manifest_path, "r") as f:
            content = f.read()
        match = re.search(r'android:name="de.kalass.familyappifier.START_URL"\s+android:value="([^"]+)"', content)
        if match:
            return match.group(1)
    except Exception as e:
        print(f"Error reading manifest file: {e}")
        
    return None

def fetch_favicon_url(base_url):
    """Fetches base_url and extracts favicon URLs from HTML, falling back to /favicon.ico."""
    print(f"Fetching start page: {base_url} ...")
    
    # Create SSL context that ignores self-signed certificate validation
    ctx = ssl._create_unverified_context()
    
    try:
        req = urllib.request.Request(
            base_url, 
            headers={'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36'}
        )
        with urllib.request.urlopen(req, context=ctx, timeout=10) as response:
            html = response.read().decode('utf-8', errors='ignore')
    except Exception as e:
        print(f"Error loading webpage: {e}")
        print("Falling back to default domain root favicon paths.")
        html = ""
 
    # Parse HTML using regex for link tags containing icons
    icon_urls = []
    # Match `<link ... rel="...icon..." ...>` or similar
    links = re.findall(r'<link\s+[^>]*rel=["\'][^"\']*(?:icon|shortcut)[^"\']*["\'][^>]*>', html, re.IGNORECASE)
    
    for link in links:
        href_match = re.search(r'href=["\']([^"\']+)["\']', link, re.IGNORECASE)
        if href_match:
            href = href_match.group(1)
            # Try to grab sizes if specified (to prioritize higher resolution icons)
            sizes_match = re.search(r'sizes=["\']([^"\']+)["\']', link, re.IGNORECASE)
            sizes = sizes_match.group(1) if sizes_match else "0x0"
            icon_urls.append((href, sizes))
            
    # Sort icons: prioritize png, then larger sizes
    png_icons = [icon for icon in icon_urls if ".png" in icon[0].lower()]
    if png_icons:
        # Sort by size (descending)
        png_icons.sort(key=lambda x: int(x[1].split('x')[0]) if 'x' in x[1] else 0, reverse=True)
        resolved_url = urllib.parse.urljoin(base_url, png_icons[0][0])
        print(f"Found PNG icon in HTML: {resolved_url} ({png_icons[0][1]})")
        return resolved_url

    if icon_urls:
        resolved_url = urllib.parse.urljoin(base_url, icon_urls[0][0])
        print(f"Found icon in HTML: {resolved_url}")
        return resolved_url

    # Standard fallbacks
    for fallback in ["/favicon.png", "/favicon.ico"]:
        fallback_url = urllib.parse.urljoin(base_url, fallback)
        try:
            req = urllib.request.Request(fallback_url, method='HEAD')
            with urllib.request.urlopen(req, context=ctx, timeout=3) as resp:
                if resp.status == 200:
                    print(f"Found root fallback icon: {fallback_url}")
                    return fallback_url
        except Exception:
            pass
            
    # Default return root favicon.png
    return urllib.parse.urljoin(base_url, "/favicon.png")

def download_image(url, output_path):
    """Downloads the icon image, ignoring SSL checks."""
    print(f"Downloading icon from {url} ...")
    ctx = ssl._create_unverified_context()
    try:
        req = urllib.request.Request(
            url, 
            headers={'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)'}
        )
        with urllib.request.urlopen(req, context=ctx, timeout=10) as response:
            with open(output_path, 'wb') as out_file:
                out_file.write(response.read())
        return True
    except Exception as e:
        print(f"Failed to download image: {e}")
        return False

def detect_background_color(img):
    """Detects whether the image is transparent, or has a solid background color."""
    width, height = img.size
    
    # Check for transparency (sample pixels across the image)
    has_transparency = False
    for x in range(0, width, max(1, width // 20)):
        for y in range(0, height, max(1, height // 20)):
            if img.getpixel((x, y))[3] < 220:
                has_transparency = True
                break
        if has_transparency:
            break
            
    if has_transparency:
        return "#ffffff" # Default to white for transparent logos
        
    # Not transparent. Sample near the 4 corners to detect a solid background color
    inset = min(5, width // 20)
    corners = [
        img.getpixel((inset, inset)),
        img.getpixel((width - 1 - inset, inset)),
        img.getpixel((inset, height - 1 - inset)),
        img.getpixel((width - 1 - inset, height - 1 - inset))
    ]
    
    # Check if all corners are very similar
    r_vals = [c[0] for c in corners]
    g_vals = [c[1] for c in corners]
    b_vals = [c[2] for c in corners]
    
    similar = True
    for vals in (r_vals, g_vals, b_vals):
        if max(vals) - min(vals) > 15:
            similar = False
            break
            
    if similar:
        # Return average corner color in hex format
        avg_r = sum(r_vals) // 4
        avg_g = sum(g_vals) // 4
        avg_b = sum(b_vals) // 4
        return f"#{avg_r:02x}{avg_g:02x}{avg_b:02x}"
        
    return "#ffffff"

def hex_to_rgb(hex_str):
    """Converts hex color string to RGB tuple."""
    hex_str = hex_str.lstrip('#')
    return tuple(int(hex_str[i:i+2], 16) for i in (0, 2, 4))

def process_adaptive_icons(source_path, res_dir):
    """Generates modern Android adaptive icon files and legacy fallbacks."""
    print("Generating adaptive icons and legacy fallbacks...")
    try:
        img = Image.open(source_path).convert("RGBA")
        width, height = img.size
        
        # 1. Detect background color of the source image
        bg_hex = detect_background_color(img)
        bg_rgb = hex_to_rgb(bg_hex)
        print(f"Detected brand background color: {bg_hex}")
        
        # 2. Generate values/ic_launcher_background.xml for adaptive background
        values_dir = os.path.join(res_dir, "values")
        os.makedirs(values_dir, exist_ok=True)
        bg_xml_path = os.path.join(values_dir, "ic_launcher_background.xml")
        with open(bg_xml_path, "w") as f:
            f.write(f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">{bg_hex}</color>
</resources>
""")
        print(f"Saved background color resource to {bg_xml_path}")

        # 3. Generate mipmap-anydpi-v26 adaptive XML files
        anydpi_dir = os.path.join(res_dir, "mipmap-anydpi-v26")
        os.makedirs(anydpi_dir, exist_ok=True)
        adaptive_xml_content = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
"""
        for xml_name in ["ic_launcher.xml", "ic_launcher_round.xml"]:
            xml_path = os.path.join(anydpi_dir, xml_name)
            with open(xml_path, "w") as f:
                f.write(adaptive_xml_content)
        print(f"Saved adaptive icon structures in {anydpi_dir}")

        # 4. Create the adaptive foreground canvas (1024x1024, transparent background)
        fg_canvas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
        # Scale the original logo to the central 60% safe zone
        target_size_fg = 614
        logo_aspect = width / height
        if logo_aspect >= 1.0:
            w_fg = target_size_fg
            h_fg = int(target_size_fg / logo_aspect)
        else:
            h_fg = target_size_fg
            w_fg = int(target_size_fg * logo_aspect)
            
        logo_resized_fg = img.resize((w_fg, h_fg), Image.Resampling.LANCZOS)
        fg_canvas.paste(logo_resized_fg, (512 - w_fg // 2, 512 - h_fg // 2), logo_resized_fg)

        # 5. Create the legacy fallback canvas (1024x1024, solid background color)
        legacy_canvas = Image.new("RGBA", (1024, 1024), bg_rgb + (255,))
        # Scale logo to 80% for legacy icon
        target_size_leg = 820
        if logo_aspect >= 1.0:
            w_leg = target_size_leg
            h_leg = int(target_size_leg / logo_aspect)
        else:
            h_leg = target_size_leg
            w_leg = int(target_size_leg * logo_aspect)
            
        logo_resized_leg = img.resize((w_leg, h_leg), Image.Resampling.LANCZOS)
        # Use transparent paste mask
        legacy_canvas.paste(logo_resized_leg, (512 - w_leg // 2, 512 - h_leg // 2), logo_resized_leg)

        # 6. Resize and save both images for all densities
        for folder, size in RESOLUTIONS.items():
            target_dir = os.path.join(res_dir, folder)
            os.makedirs(target_dir, exist_ok=True)
            
            # Save legacy icon
            legacy_resized = legacy_canvas.resize((size, size), Image.Resampling.LANCZOS)
            legacy_resized.save(os.path.join(target_dir, "ic_launcher.png"), "PNG")
            
            # Save adaptive foreground layer
            fg_resized = fg_canvas.resize((size, size), Image.Resampling.LANCZOS)
            fg_resized.save(os.path.join(target_dir, "ic_launcher_foreground.png"), "PNG")
            
        print("Successfully generated all launcher PNG sizes.")
        return True
    except Exception as e:
        print(f"Error generating adaptive icons: {e}")
        return False

def process_app_module(app_dir):
    """Processes a single app module directory to download and resize its launcher icon."""
    print(f"\n==========================================")
    print(f"Processing module: {app_dir}")
    print(f"==========================================")
    
    manifest_path = os.path.join(app_dir, "src/main/AndroidManifest.xml")
    res_dir = os.path.join(app_dir, "src/main/res")
    
    start_url = parse_start_url(manifest_path)
    if not start_url:
        print(f"Error: Could not retrieve START_URL from Manifest at {manifest_path}")
        return False
        
    print(f"Target site configured: {start_url}")
    
    temp_download = f"temp_downloaded_icon_{app_dir}"
    favicon_url = fetch_favicon_url(start_url)
    
    # Extract extension or default to .png
    parsed = urllib.parse.urlparse(favicon_url)
    ext = os.path.splitext(parsed.path)[1]
    if not ext:
        ext = ".png"
    temp_file = temp_download + ext
    
    if os.path.exists(temp_file):
        os.remove(temp_file)
        
    if download_image(favicon_url, temp_file):
        success = process_adaptive_icons(temp_file, res_dir)
        if os.path.exists(temp_file):
            os.remove(temp_file)
            
        if success:
            print(f"🎉 Success! Adaptive launcher icons for {app_dir} have been successfully updated.")
            return True
        else:
            print(f"❌ Error: Failed to process and resize the icon images for {app_dir}.")
            return False
    else:
        print(f"❌ Error: Could not download the favicon from {favicon_url}")
        return False

def main():
    # Exclusion list
    EXCLUDED_MODULES = {"app-vogelchat"}
    
    # If target modules are provided as arguments
    if len(sys.argv) > 1:
        targets = sys.argv[1:]
        for target in targets:
            # Normalize path (remove trailing slashes)
            target = target.rstrip("/")
            if target in EXCLUDED_MODULES:
                print(f"Skipping excluded module: {target}")
                continue
            if not os.path.isdir(target):
                print(f"Error: Directory '{target}' does not exist.")
                continue
            process_app_module(target)
    else:
        # Auto-detect app-* modules
        print("No target specified. Scanning for app modules...")
        app_modules = []
        for name in os.listdir("."):
            if os.path.isdir(name) and name.startswith("app-") and name not in EXCLUDED_MODULES:
                app_modules.append(name)
        
        if not app_modules:
            print("No app modules found to process.")
            sys.exit(0)
            
        print(f"Found modules to process: {', '.join(app_modules)}")
        success_count = 0
        for module in app_modules:
            if process_app_module(module):
                success_count += 1
                
        print(f"\nFinished! Updated {success_count}/{len(app_modules)} modules.")

if __name__ == "__main__":
    main()
