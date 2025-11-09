import argparse, shutil, os

parser = argparse.ArgumentParser()
parser.add_argument("--namespace")
args = parser.parse_args()

path = f"./chroma/index/{args.namespace}"
if os.path.exists(path):
    shutil.rmtree(path)
    print(f"✅ Deleted namespace: {args.namespace}")
else:
    print(f"⚠️ Namespace not found: {args.namespace}")
