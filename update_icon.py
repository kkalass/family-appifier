#!/usr/bin/env python3
import os
import re
import sys
import ssl
import subprocess
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET

# Configuration
MANIFEST_PATH = "app-immich/src/main/AndroidManifest.xml"
RES_DIR = "app-immich/src/main/res"

# Standard Android mipmap resolutions
RESOLUTIONS = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192
}

def parse_start_url():
    """Extracts START_URL from the AndroidManifest.xml meta-data."""
    if not os.path.exists(MANIFEST_PATH):
        print(f"Error: Manifest file not found at {MANIFEST_PATH}")
        sys.exit(1)
        
    try:
        # Register namespaces to prevent namespace prefix issues in parsing
        ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
        ET.register_namespace("tools", "http://schemas.android.com/tools")
        
        tree = ET.parse(MANIFEST_PATH)
        root = tree.getroot()
        
        # Search for the START_URL meta-data tag
        for meta in root.findall(".//meta-data"):
            name = meta.get("{http://schemas.android.com/apk/res/android}name")
            if name == "de.kalass.webviewwrapper.START_URL":
                return meta.get("{http://schemas.android.com/apk/res/android}value")
    except Exception as e:
        print(f"Warning: Failed to parse manifest XML ({e}). Attempting regex fallback.")
        
    # Regex fallback if XML parsing fails due to complex templates
    try:
        with open(MANIFEST_PATH, "r") as f:
            content = f.read()
        match = re.search(r'android:name="de.kalass.webviewwrapper.START_URL"\s+android:value="([^"]+)"', content)
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

def resize_icon_with_sips(source_path):
    """Converts and resizes the source icon to all required Android resolutions using sips."""
    temp_png = "temp_icon_source.png"
    
    # Clean up any existing temp files
    if os.path.exists(temp_png):
        os.remove(temp_png)

    # Convert source image to PNG format first
    print("Converting source icon to PNG format...")
    try:
        result = subprocess.run(
            ["sips", "-s", "format", "png", source_path, "--out", temp_png],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE
        )
        if result.returncode != 0:
            print(f"Error converting with sips: {result.stderr.decode()}")
            return False
    except Exception as e:
        print(f"Failed to execute sips tool: {e}")
        return False

    # Generate the resized icons for each density
    for folder, size in RESOLUTIONS.items():
        target_dir = os.path.join(RES_DIR, folder)
        os.makedirs(target_dir, exist_ok=True)
        target_path = os.path.join(target_dir, "ic_launcher.png")
        
        print(f"Generating icon: {folder} ({size}x{size}) -> {target_path}")
        try:
            subprocess.run(
                ["sips", "-z", str(size), str(size), temp_png, "--out", target_path],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL
            )
        except Exception as e:
            print(f"Failed to resize icon for {folder}: {e}")
            return False
            
    # Clean up temp files
    if os.path.exists(temp_png):
        os.remove(temp_png)
    return True

def main():
    start_url = parse_start_url()
    if not start_url:
        print("Error: Could not retrieve START_URL from Manifest.")
        sys.exit(1)
        
    print(f"Target site configured: {start_url}")
    
    temp_download = "temp_downloaded_icon"
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
        success = resize_icon_with_sips(temp_file)
        if os.path.exists(temp_file):
            os.remove(temp_file)
            
        if success:
            print("\n🎉 Success! All Android app launcher icons have been successfully updated using the site's favicon.")
        else:
            print("\n❌ Error: Failed to process and resize the icon images.")
    else:
        print(f"\n❌ Error: Could not download the favicon from {favicon_url}")

if __name__ == "__main__":
    main()
