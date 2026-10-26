# Ansible — benchmark VPS environment

Two playbooks, run from this directory:

| Playbook | Does |
| --- | --- |
| `setup-env.yml` | packages + directory skeleton on the VPS (`openjdk-17-jdk-headless`, `jq`, `rsync`, `util-linux`, `python3`, `python3-yaml`, `tmux`) |
| `deploy.yml` | stop + wipe the VPS tree, rsync jar + runner + datasets, then start the campaign |

`deploy.yml` builds nothing on the VPS: the jar is built on the control node and
started with `BENCH_SKIP_BUILD=1`, so the host needs no Maven toolchain.

## Install Ansible (once, on your machine)

No pip needed on Debian/Ubuntu — `ansible-core` is packaged and is enough,
because the playbook only uses `ansible.builtin` modules:

```bash
sudo apt install -y ansible-core      # controller only, ~2.19 on trixie
ansible-playbook --version
```

Alternatives, if you prefer:

```bash
sudo apt install -y ansible           # full package, adds community collections
pipx install ansible-core             # isolated Python install
python3 -m pip install --user ansible-core   # plain pip (may need --break-system-packages)
```

Ansible connects over SSH, so nothing has to be installed on the VPS.

## 1. Provision the environment (`setup-env.yml`)

```bash
ssh-keyscan -H 103.82.192.49 >> ~/.ssh/known_hosts   # first run only
cd benchmark/compute/.ansible
ansible-playbook setup-env.yml -k        # -k prompts for the SSH password
```

`-k` is only needed when you log in with a password. With an SSH key in
`ssh-agent` it is not required:

```bash
ssh-copy-id root@103.82.192.49
ansible-playbook setup-env.yml
```

No `-K`/`become` is needed because the playbook logs in as `root`. If you switch
the inventory to a sudo user, add `-K` and set `become: true` on the play.

The password is deliberately **not** stored in `inventory.ini`; keep it that way
so it never lands in git. If you must automate it, put it in an
`ansible-vault`-encrypted `group_vars/vault.yml` as
`ansible_password: ...` and run with `--ask-vault-pass`.

## 2. Deploy and run (`deploy.yml`)

```bash
./benchmark/runner/build_artifact.sh     # build the jar on the control node
ansible-playbook deploy.yml -k           # wipe, sync, then start the campaign
ansible-playbook deploy.yml --tags clean # wipe the VPS tree only
ansible-playbook deploy.yml --tags sync  # wipe + sync only
ansible-playbook deploy.yml --tags run   # start only
ssh root@103.82.192.49 'cd ~/top-k-husm-mg && ./benchmark/runner/stop_benchmark.sh'
```

Every sync is a **clean deploy**: the play stops any campaign still running, then
deletes `benchmark_project_root` entirely (jar, runner, datasets, `results/`)
and rebuilds the directory skeleton before rsync. Nothing from an older build
can survive, so a deploy never mixes a new jar with an old `runs.csv`.

Switch it off in `group_vars/all.yml`:

| Variable | Default | Effect |
| --- | --- | --- |
| `benchmark_clean_deploy` | `true` | wipe the VPS tree before every sync |
| `benchmark_clean_keep_results` | `false` | with the above, keep `benchmark/results/` (and delete `artifact/`, `benchmark/runner/`, `benchmark/dataset/` only) so an interrupted campaign resumes |

To resume a long campaign, set both to
`benchmark_clean_deploy: true` + `benchmark_clean_keep_results: true` (or
`benchmark_clean_deploy: false` to keep everything).

What is pushed (`benchmark_push` in `group_vars/all.yml`, relative to the repo
root on both sides):

- `artifact/target/` — the jar, with `--delete` so stale jars disappear;
- `benchmark/runner/` — `run_benchmark.sh`, `config.py`, `benchmark.yaml`, ...;
- `benchmark/dataset/formated/` — ~22 MB of datasets.

How it works:

- the wipe runs `stop_benchmark.sh` first when the VPS still has a runner, so
  no JVM is left running on deleted files, and then recreates the skeleton
  (`benchmark_dirs`) because rsync only creates the last path element;
- the first `--tags sync` creates `~/.ssh/id_ed25519` on the control node when
  there is no key, authorizes it on the VPS (`authorized_key`) and then runs
  `rsync --rsh 'ssh -o BatchMode=yes'`, so nothing prompts. Once the key is in
  place, `-k` is no longer needed;
- pushed files are re-owned to the login user (`-a` would keep the control
  node's uid);
- `benchmark_java_opts` (default `""` = use `run.heap` from `benchmark.yaml`)
  sets `BENCH_JAVA_OPTS`, e.g. `-e benchmark_java_opts=-Xmx3g`;
- the campaign detaches itself and the playbook prints the pid and the log path;
  stop it with `stop_benchmark.sh`, as shown above.

## Tags

| Command | Effect |
| --- | --- |
| `ansible-playbook setup-env.yml -k --tags packages` | install/update packages only |
| `ansible-playbook setup-env.yml -k --tags dirs` | create the directory skeleton only |
| `ansible-playbook setup-env.yml -k --tags verify` | check versions, no changes |
| `ansible-playbook deploy.yml --tags clean` | wipe the VPS tree (stop + delete + skeleton) |
| `ansible-playbook deploy.yml --tags sync` | wipe, then push jar, runner and datasets |
| `ansible-playbook deploy.yml --tags run` | start the campaign only |
| `ansible-playbook setup-env.yml -k --check --diff` | dry run |

## What it checks

- OS is Debian/Ubuntu (apt);
- `java -version` reports the major version in `benchmark_java_major`
  (default `17`, matching `artifact/pom.xml`);
- `python3 -c "import yaml"` works, as required by
  `benchmark/runner/config.py`;
- `jq`, `rsync`, `flock` and `setsid` are callable — `run_benchmark.sh` needs
  all four (`flock` for the shared log, `setsid` for the detached campaign).

## Notes

- `apt-get -y --fix-broken install` runs first; that is the Ansible equivalent
  of the `--fix-missing` flag in the manual command at
  `benchmark/compute/vps/README.MD`.
- `benchmark_create_dirs: false` in `group_vars/all.yml` makes the playbook
  package-only.
- The playbook is idempotent: re-running it changes nothing when the host is
  already provisioned.
- The SSH password used by `-k` is never written to disk (nothing in
  `inventory.ini`), so no credential ends up in git.
