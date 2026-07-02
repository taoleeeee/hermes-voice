import os
import urllib.request
import tarfile
import shutil

def main():
    assets_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets"))
    espeak_dest = os.path.join(assets_dir, "espeak-ng-data")
    tokens_dest = os.path.join(assets_dir, "tokens.txt")

    # If assets already exist, skip
    if os.path.exists(espeak_dest) and os.path.exists(tokens_dest):
        print("Assets already prepared, skipping.")
        return

    os.makedirs(assets_dir, exist_ok=True)

    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"
    tmp_file = os.path.join(os.path.dirname(__file__), "kokoro-en-v0_19.tar.bz2")
    extract_dir = os.path.join(os.path.dirname(__file__), "temp_extract")

    print(f"Downloading {url}...")
    urllib.request.urlretrieve(url, tmp_file)

    print("Extracting files...")
    os.makedirs(extract_dir, exist_ok=True)
    with tarfile.open(tmp_file, "r:bz2") as tar:
        # Extract only espeak-ng-data and tokens.txt to keep it fast
        for member in tar.getmembers():
            if "espeak-ng-data" in member.name or "tokens.txt" in member.name:
                tar.extract(member, extract_dir)

    # Move to assets
    src_dir = os.path.join(extract_dir, "kokoro-en-v0_19")
    src_espeak = os.path.join(src_dir, "espeak-ng-data")
    src_tokens = os.path.join(src_dir, "tokens.txt")

    if os.path.exists(src_espeak):
        if os.path.exists(espeak_dest):
            shutil.rmtree(espeak_dest)
        shutil.move(src_espeak, espeak_dest)
        print(f"Moved espeak-ng-data to {espeak_dest}")

    if os.path.exists(src_tokens):
        if os.path.exists(tokens_dest):
            os.remove(tokens_dest)
        shutil.move(src_tokens, tokens_dest)
        print(f"Moved tokens.txt to {tokens_dest}")

    # Cleanup
    print("Cleaning up temporary files...")
    shutil.rmtree(extract_dir, ignore_errors=True)
    if os.path.exists(tmp_file):
        os.remove(tmp_file)
    print("Done!")

if __name__ == "__main__":
    main()
