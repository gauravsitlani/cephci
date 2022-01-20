import logging
from utility.utils import setup_cluster_access
import re
import yaml

log = logging.getLogger(__name__)

DIR = {
    "v2": {
        "script": "/ceph-qe-scripts/rgw/v2/tests/nfs_ganesha/",
        "lib": "/ceph-qe-scripts/rgw/v2/lib/",
        "config": "/ceph-qe-scripts/rgw/v2/tests/nfs_ganesha/config/",
    },
}

def run(ceph_cluster, **kw):
    rgw_ceph_object = ceph_cluster.get_ceph_object("rgw")
    client_ceph_object = ceph_cluster.get_ceph_object("client")
    rgw_node = rgw_ceph_object.node
    client_node = client_ceph_object.node
    rgw_node_host = rgw_node.shortname
    client_node_host = client_node.shortname
    test_folder = "rgw-tests"
    test_folder_path = f"~/{test_folder}"
    git_url = "https://github.com/rakeshgm/ceph-qe-scripts.git"
    git_clone = f"git clone {git_url} -b nfs-exeception"
    client_node.exec_command(
        cmd=f"sudo rm -rf {test_folder}"
        + f" && mkdir {test_folder}"
        + f" && cd {test_folder}"
        + f" && {git_clone}"
    )

    config = kw.get("config")
    script_name = config.get("script-name")
    config_file_name = config.get("config-file-name")
    test_version = config.get("test-version", "v2")
    script_dir = DIR[test_version]["script"]
    config_dir = DIR[test_version]["config"]
    timeout = config.get("timeout", 300)

    # Clone the repository once for the entire test suite
    pip_cmd = "venv/bin/pip"
    python_cmd = "venv/bin/python"
    out, err = client_node.exec_command(cmd="ls -l venv", check_ec=False)

    if not out.read().decode():
        client_node.exec_command(
            cmd="yum install python3 -y --nogpgcheck", check_ec=False, sudo=True
        )
        client_node.exec_command(cmd="python3 -m venv venv")
        client_node.exec_command(cmd=f"{pip_cmd} install --upgrade pip")

        client_node.exec_command(
            cmd=f"{pip_cmd} install "
                + f"-r {test_folder}/ceph-qe-scripts/rgw/requirements.txt"
        )

        if ceph_cluster.rhcs_version.version[0] == 5:
            setup_cluster_access(ceph_cluster, rgw_node)
            setup_cluster_access(ceph_cluster, client_node)
            rgw_node.exec_command(
                sudo=True, cmd="yum install -y ceph-common ceph-radosgw --nogpgcheck"
            )
            client_node.exec_command(
                sudo=True, cmd="yum install -y ceph-common --nogpgcheck"
            )
        if ceph_cluster.rhcs_version.version[0] in [3, 4]:
            if ceph_cluster.containerized:
                # install ceph-radosgw on the host hosting the container
                rgw_node.exec_command(
                    sudo=True, cmd="yum install -y ceph-common ceph-radosgw --nogpgcheck"
                )
                client_node.exec_command(
                    sudo=True, cmd="yum install -y ceph-common --nogpgcheck"
                )
    # Mount point ops
    mount_dir = config.get("mount-dir", "/mnt/ganesha/")
    checkdir_cmd = f"[ -d '{mount_dir}' ] && [ ! -L '{mount_dir}' ] && echo 'Directory {mount_dir} exists.' || echo 'Error: Directory {mount_dir} exists but point to $(readlink -f {mount_dir}).'"
    out , err = client_node.exec_command(
        sudo=True, cmd=checkdir_cmd, check_ec=False
    )
    mount_dir_exists = f"Directory {mount_dir} exists."
    if out.read().decode() == mount_dir_exists:
        umount_ganesha_mountpt = f"umount {mount_dir}"
        remove_ganesha_mountpt = f"rm -rf {mount_dir}"
        client_node.exec_command(sudo=True, cmd=umount_ganesha_mountpt, check_ec=False)
        client_node.exec_command(sudo=True, cmd=remove_ganesha_mountpt, check_ec=False)

    client_node.exec_command(cmd=f"mkdir {mount_dir}", check_ec=False, sudo=True)
    nfs_version = config.get("nfs-version","4")
    # Mount command for nfs-ganesha : mount -t nfs -o nfsvers=<nfs version>,noauto,soft,sync,proto=tcp `hostname -s`:/ /mnt/ganesha/
    mount_cmd = f"mount -t nfs -o nfsvers={nfs_version},noauto,soft,sync,proto=tcp {rgw_node_host}:/ {mount_dir}"
    client_node.exec_command(cmd=mount_cmd, check_ec=False, sudo=True)
    log.info("nfs ganesha mounted successfully on the mountpoint")
    # To parse the nfs-ganesha configuration file : /etc/ganesha/ganesha.conf
    v_as_out , err = rgw_node.exec_command(cmd="cat /etc/ganesha/ganesha.conf", check_ec=False, sudo=True)
    clean = lambda x: re.sub('[^A-Za-z0-9]+', '', x)
    ganesha_conf_out = v_as_out.read().decode()
    ganesha_conf = ganesha_conf_out.split("\n")
    for content in ganesha_conf:
        if 'Access_Key_Id' in content:
            access_key = clean(content.split('=')[1])
        if 'Secret_Access_Key' in content:
            secret_key = clean(content.split('=')[1])
        if 'User_Id' in content:
            rgw_user_id = clean(content.split('=')[1])

    rgw_user_config = dict(user_id=rgw_user_id,
                           access_key=access_key,
                           secret_key=secret_key,
                           rgw_hostname=rgw_node_host, #short hostname of rgw to populate under rgw_user.yaml
                           ganesha_config_exists=True,
                           already_mounted=True,
                           nfs_version=nfs_version,
                           nfs_mnt_point=mount_dir,
                           Pseudo="cephobject"
                           )

    rgw_user_config_fname = 'rgw_user.yaml' # to create this file in destination with the above values : ceph-qe-scripts/rgw/v2/tests/nfs_ganesha/config/
    local_file = "/home/cephuser/rgw-tests/ceph-qe-scripts/rgw/v2/tests/nfs_ganesha/config/" + rgw_user_config_fname
    log.info('creating rgw_user.yaml : %s' % rgw_user_config)
    local_conf_file = client_node.remote_file(file_name=local_file, file_mode="w")
    local_conf_file.write(yaml.dump(rgw_user_config, default_flow_style=False))
    log.info("rgw_user.yaml file written")

    test_config = {"config": config.get("test-config", {})}
    if test_config["config"]:
        log.info("creating custom config")
        f_name = test_folder + config_dir + config_file_name
        remote_fp = client_node.remote_file(file_name=f_name, file_mode="w")
        remote_fp.write(yaml.dump(test_config, default_flow_style=False))

    out, err = client_node.exec_command(
        cmd=f"sudo {python_cmd} "
        + test_folder_path
        + script_dir
        + script_name
        + " -c "
        + test_folder
        + config_dir
        + config_file_name
        + " -r" + local_file,
        timeout=timeout,
    )
    log.info(out.read().decode())
    log.error(err.read().decode())

    cleanup_ops = config.get("cleanup-ops")
    if cleanup_ops:
        cleanup = cleanup_ops.get("cleanup",True)
        do_unmount = cleanup_ops.get("do-unmount",True)
        if cleanup:
            client_node.exec_command(cmd=f"rm -rf {mount_dir}/cephobject/*",check_ec=False,sudo=True)
        if do_unmount:
            client_node.exec_command(cmd=f"umount {mount_dir}", check_ec=False, sudo=True)

    return 0

