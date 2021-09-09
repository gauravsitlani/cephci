ceph_pkgs = requests.get(
                    base_url + "/compose/Tools/x86_64/os/Packages/"
                )
                m = re.search(r"ceph-common-(.*?).x86", ceph_pkgs.text)
                ceph_version.append(m.group(1))
                m = re.search(r"ceph-ansible-(.*?).rpm", ceph_pkgs.text)
                ceph_ansible_version.append(m.group(1))
                log.info("Compose id is: " + compose_id)
