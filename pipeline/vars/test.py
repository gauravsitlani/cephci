import requests

def fetch_ceph_version(
    base_url: str
) -> None:
   
    ceph_pkgs = requests.get(
      base_url + "/compose/Tools/x86_64/os/Packages/"
    )
    ceph_version = re.search(r"ceph-common-(.*?).x86", ceph_pkgs.text).group(1)
    print(f"ceph_version is: {ceph_version}")
    return ceph_version
  
