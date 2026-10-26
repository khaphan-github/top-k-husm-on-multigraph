# Environment Setup Notes

## 1. Python

```bash
# Run on local.
sudo apt update
sudo apt install python3 python3-pip
pip install -r requirements.txt --break-system-packages
python3 --version
```



## 2. Run on VPS

### Flow


```mermaid
sequenceDiagram
    autonumber
    actor U as Local machine
    participant A as Ansible
    participant V as VPS
    participant J as JVM benchmark
    participant P as report_viewer

    Note over U,V: FLOW 1 - SYNC VPS
    U->>V: setup-env.yml first run, install JDK 17, jq, rsync, tmux
    U->>U: build_artifact.sh, mvn package
    U->>A: deploy.yml
    A->>V: stop_benchmark.sh, stop previous campaign
    A->>V: wipe ~/top-k-husm-mg, clean deploy
    A->>V: recreate skeleton dirs
    A->>V: rsync jar, runner, formatted dataset
    A->>J: run_benchmark.sh with BENCH_SKIP_BUILD and setsid
    J->>V: write runs.csv, config.yaml, logs/benchmark.log
    Note over J,V: run in background, resume based on runs.csv

    Note over U,J: FLOW 2 - DOWNLOAD REPORT AND VISUALIZE
    U->>V: rsync benchmark/results to benchmark/results/acsd
    V-->>U: runs.csv and logs
    U->>P: ReportViewer with runs.csv
    P->>P: metrics, coverage, plot_all
    P-->>U: figures and tables in benchmark/visualize/out
```

```bash
# Setup (one time)
ansible-playbook benchmark/compute/.ansible/setup-env.yml -k

# Run: build locally, then deploy and run the campaign on the VPS
./benchmark/runner/build_artifact.sh
ansible-playbook benchmark/compute/.ansible/deploy.yml -k

# Download the report locally for visualization
rsync -avz root@<vps-host>:~/top-k-husm-mg/benchmark/results/ benchmark/results/acsd
```

## 3. Measuring memory (benchmark)
Peak memory follows the SPMF `MemoryLogger` convention
(https://data-mining.philippe-fournier-viger.com/spmfs-architecture-4-the-memorylogger/):
