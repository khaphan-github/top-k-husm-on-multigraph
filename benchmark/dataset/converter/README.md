# Pre-processing — chuyển dataset thô sang edge-list của `MgGraphLoader`

Mỗi dataset trong `benchmark/dataset/raw_data/` có **đúng một class Python**
`<DatasetName>Converter`, chạy ra **đúng một file** trong
`benchmark/dataset/formated/`.

## Định dạng đích

```
src dst label1[,label2,...]
```

- `src < dst` (đồ thị vô hướng), id đỉnh đã dồn về `0..n-1`;
- mỗi nhãn trong danh sách = **một cạnh song song** thuộc type đó, nên
  `4 5 8,8,24` nghĩa là 2 cạnh type 8 + 1 cạnh type 24 (đây là cách
  `MgGraphLoader` dựng lại `mult`);
- một dòng ứng với một cặp đỉnh, các dòng được sắp theo `(src, dst)`, nhãn trong
  dòng sắp tăng dần ⇒ file tái lập được từng byte;
- self-loop bị bỏ (loader cũng bỏ);
- **không có nhãn đỉnh**: artifact tự sinh nhãn đỉnh
  (`--label-mode degree|random`), nên thuộc tính đỉnh trong dữ liệu thô (phòng
  ban, node label, …) không được ghi vào file.

## Danh sách converter

| Class | File nguồn (`dataset/raw_data/`) | File đích (`dataset/formated/`) |
|---|---|---|
| `CollegeMsgConverter` | `CollegeMsg/CollegeMsg.txt` | `college-msg.txt` |
| `EmailEuCoreTemporalConverter` | `email-Eu-core-temporal/email-Eu-core-temporal.txt` | `email-eu-core-temporal.txt` |
| `SxAskubuntuConverter` | `sx-askubuntu/sx-askubuntu.txt` | `sx-askubuntu.txt` |
| `SxMathoverflowConverter` | `sx-mathoverflow/sx-mathoverflow.txt` | `sx-mathoverflow.txt` |
| `SxSuperuserConverter` | `sx-superuser/sx-superuser.txt` | `sx-superuser.txt` |
| `BnFlyDrosophilaConverter` | `bn-fly-drosophila/bn-fly-drosophila_medulla_1.edges` | `bn-fly-drosophila.txt` |
| `DblpMappedgraphConverter` | `DBLP-Mappedgraph/multigraph/edges.txt` (mặc định) | `dblp-multigraph.txt` |

Kết quả lần chạy gần nhất (10 bucket thời gian, cap mặc định):

| Dataset | Đỉnh | Cặp | Cạnh | Type | Max song song | Seed vector |
|---|---:|---:|---:|---:|---:|---:|
| college-msg | 1.899 | 13.838 | 36.821 | 10 | 16 | 104.536 |
| email-eu-core-temporal | 986 | 16.064 | 90.429 | 8 | 16 | 1.743.171 |
| sx-askubuntu | 159.316 | 455.691 | 682.747 | 10 | 16 | 1.201.502 |
| sx-mathoverflow | 24.818 | 187.986 | 349.507 | 10 | 16 | 1.074.316 |
| sx-superuser | 194.085 | 714.570 | 1.065.009 | 10 | 16 | 1.953.751 |
| bn-fly-drosophila | 1.781 | 8.911 | 33.508 | 1 | 328 | 42.419 |
| dblp-multigraph | 83.901 | 141.471 | 159.302 | 50 | 11 | 333.756 |

## Cách chạy

```bash
# tất cả dataset (mặc định ghi vào benchmark/dataset/formated)
python3 benchmark/dataset/converter/convert_all.py

# chỉ một dataset
python3 benchmark/dataset/converter/college_msg_converter.py

# tuỳ chọn
python3 benchmark/dataset/converter/convert_all.py --only college-msg dblp-multigraph
python3 benchmark/dataset/converter/convert_all.py --types none --time-buckets 24
python3 benchmark/dataset/converter/convert_all.py --max-types 3 --max-mult 2
python3 benchmark/dataset/converter/sx_superuser_converter.py --out /tmp/sx.txt
```

Chạy script trực tiếp (không phải `python -m`) — các converter import lẫn nhau
theo đường dẫn thư mục script.

## Quy ước chuyển đổi

1. **Dồn id đỉnh**: SNAP dùng id thưa (`sx-superuser` chỉ có 194.085 đỉnh nhưng
   id lớn nhất 567.315). Loader cấp mảng `n = max(id)+1` và chia bucket bậc theo
   `n`, nên id thưa sẽ tạo hàng trăm nghìn đỉnh rỗng ở bucket bậc thấp ⇒ luôn
   relabel về `0..n-1`.
2. **Vô hướng hoá**: cạnh `u→v` và `v→u` gộp chung, ghi bằng `min max`.
3. **Bỏ self-loop**: `sx-mathoverflow` có 116.109, `sx-superuser` có 334.600.
4. **Type của mạng theo thời gian** (`u v timestamp`, không có nhãn cạnh):
   - `--types bucket` (mặc định, 10 bucket): chia `[min_ts, max_ts]` thành các
     cửa sổ đều nhau, type = chỉ số cửa sổ ⇒ một cặp có cạnh song song thuộc
     nhiều type khác nhau, nhãn `VecLabel = (type, mult)` mới có ý nghĩa;
   - `--types none`: mọi cạnh type 0, bội số = số lần tương tác (góc nhìn
     "đa đồ thị ↔ đồ thị có trọng số").
5. **Type của `bn-fly-drosophila`**: file chỉ có `u v`, nên mọi cạnh là type 0 và
   bội số chính là số synapse (cường độ kết nối).
6. **Type của `DBLP-Mappedgraph`**: giữ nguyên nhãn gốc (0..49 cho multigraph),
   chỉ chuẩn hoá thứ tự/định dạng.

## Vì sao có cap `--max-types` / `--max-mult`

`MgHUSM` gọi `MultiGraph.seedTypes()`, hàm này liệt kê **mọi vector bội số**
của từng cặp: `prod(count[t] + 1)` vector/cặp. Dữ liệu thời gian rất nặng đuôi
— cặp `(774, 947)` của email-Eu có 4.992 email — nếu giữ đủ thì riêng cặp đó đã
sinh ~`10^13` vector và JVM nổ heap ngay khi khởi tạo (đã thử: `OutOfMemoryError`
tại `MultiGraph.seedTypes`).

Mặc định cho 4 dataset thời gian: giữ **4 type đông nhất** của mỗi cặp, mỗi type
giữ **tối đa 4 cạnh song song** ⇒ trần `5^4 = 625` vector/cặp, thấp hơn mức
2.048 của DBLP multigraph (dữ liệu tham chiếu của MuGraM).

- `bn-fly-drosophila` **không cap bội số** (chỉ 1 type nên chi phí tuyến tính,
  max 329 vector/cặp) — giữ nguyên cường độ synapse.
- `dblp-multigraph` **không cap** — đây là dữ liệu tham chiếu, max 11 cạnh/cặp.
- `--max-types 0` / `--max-mult 0` = bỏ cap (chỉ dùng khi đã tăng `-Xmx`).

## Dùng file kết quả với artifact

```bash
java -cp artifact/target/top-k-husm-mg-0.1.0-SNAPSHOT.jar topk.husm.mg.cli.MgTopKMain \
  college-msg --edges-file benchmark/dataset/formated/college-msg.txt \
  --K 10 --mer 1 --nlabels 20 --label-mode random --ieu-bound ivu
```

Thêm `--out FILE` để lưu kết quả chạy ra file text (config, thống kê tìm kiếm và
top-K pattern), hoặc `--out DIR/` để tự sinh tên file trong thư mục đó:

```bash
java -cp artifact/target/top-k-husm-mg-0.1.0-SNAPSHOT.jar topk.husm.mg.cli.MgTopKMain \
  college-msg --edges-file benchmark/dataset/formated/college-msg.txt \
  --K 10 --mer 1 --nlabels 20 --out results/
```

Muốn chạy bài toán `minUtil` (không có `K`, ngưỡng cố định) thì dùng entry point riêng:

```bash
java -cp artifact/target/top-k-husm-mg-0.1.0-SNAPSHOT.jar topk.husm.mg.cli.MgMinUtilMain \
  college-msg --edges-file benchmark/dataset/formated/college-msg.txt --min-util 50
```

`--out-all` ghi thêm toàn bộ pattern đã đánh giá có `s > 0` (không chỉ top-K).
`MgBenchOne`/`MgBenchMugram` cũng nhận `--out FILE` để lưu dòng JSON kết quả.

`MgGraphLoader` nhận khoảng trắng tuỳ ý giữa 3 cột và bỏ qua dòng trống, nhưng
**không** bỏ qua id âm hay id không liên tục — đó là lý do bước relabel ở trên.
