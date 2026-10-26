# Run on VPS
```bash
./benchmark/runner/build_artifact.sh
cd benchmark/compute/.ansible 
ansible-playbook setup-env.yml -k
ansible-playbook deploy.yml -k

rsync -avz root@103.82.192.49:~/top-k-husm-mg/benchmark/results/ benchmark/results/acsd
```