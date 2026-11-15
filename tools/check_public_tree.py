"""Local publish guard. Prints paths/reasons only, never matched secret values."""
from pathlib import Path
import argparse,subprocess,re,json
p=argparse.ArgumentParser();p.add_argument('--index',action='store_true');a=p.parse_args()
root=Path(__file__).resolve().parents[1]
secret=re.compile(rb'AIza[0-9A-Za-z_-]{35}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')
if a.index:
 names=subprocess.check_output(['git','-C',str(root),'ls-files','-z']).decode().split('\0');files=[root/n for n in names if n]
else:
 files=[f for f in root.rglob('*') if f.is_file() and not any(x in f.relative_to(root).parts for x in ['.git','.gradle','.kotlin','.idea','build','validation']) and f.name not in ['local.properties','maps.local.properties']]
findings=[]
for f in files:
 r=f.relative_to(root).as_posix();why=[]
 if f.suffix.lower() in ['.mp3','.wav','.ogg','.flac','.npy','.npz','.bin','.apk','.jks','.pem','.keystore','.jsonl','.svg'] or f.name in ['local.properties','maps.local.properties'] or f.name.startswith('.env'):why.append('forbidden_public_file')
 if f.suffix.lower() not in ['.png','.ttf','.jar']:
  b=f.read_bytes()
  if secret.search(b):why.append('credential_pattern')
  if re.search(rb'[A-Za-z]:[\\/]+Users[\\/]+[A-Za-z0-9_]',b):why.append('personal_absolute_path')
 if why:findings.append({'path':r,'issues':why})
print(json.dumps({'files':len(files),'findings':findings},indent=2));raise SystemExit(bool(findings))
