# NHÓM 2 — ĐA ĐỒ THỊ (Multigraphs) — 6 tập

**Đa đồ thị** cho phép **nhiều cạnh song song** giữa cùng một cặp đỉnh (và có thể có khuyên).
Hai nguồn sinh đa đồ thị tiêu biểu, đều có mặt ở đây:

1. **Mạng tương tác theo thời gian (temporal)** — mỗi lần A liên hệ B (tin nhắn, email, trả
   lời) là **một cạnh riêng** kèm timestamp. *(5 tập SNAP)*
2. **Mạng sinh học (connectome)** — nhiều **synapse** giữa cùng hai nơ-ron tạo ra cạnh song
   song; bội số cạnh = cường độ kết nối. *(bn-fly-drosophila)*

**Kiểm chứng từ dữ liệu:** số cạnh thực tế ≫ số cặp đỉnh duy nhất ⇒ tồn tại rất nhiều cạnh
song song (cột "Cạnh song song"). Đây là bằng chứng định lượng cho tính đa đồ thị.

| Tập | Đỉnh | Cạnh | Cặp duy nhất (có hướng) | Cạnh song song | Self-loop | Loại |
|-----|-----:|-----:|------------------------:|---------------:|----------:|------|
| CollegeMsg | 1.899 | 59.835 | 20.296 | 39.539 | 0 | Temporal |
| email-Eu-core-temporal | 986 | 332.334 | 24.929 | 307.405 | 0 | Temporal |
| sx-mathoverflow | 24.818 | 506.550 | 239.978 | 266.572 | 116.109 | Temporal |
| sx-askubuntu | 159.316 | 964.437 | 596.933 | 367.504 | 237.776 | Temporal |
| sx-superuser | 194.085 | 1.443.339 | 924.886 | 518.453 | 334.600 | Temporal |
| bn-fly-drosophila | 1.781 | 33.641 | 9.735 | 23.906 | 133 | Sinh học |

> **Mẹo:** gộp các cạnh trùng (cùng `u→v`) và đếm số lần ⇒ thu được **đồ thị có trọng số**
> (trọng số = số lần tương tác / số synapse). Nhóm này vì thế cũng minh hoạ mối liên hệ
> đa đồ thị ↔ đồ thị có trọng số.

---

## A. Mạng tương tác theo thời gian (SNAP — định dạng `u v timestamp`)

Đỉnh = người dùng; mỗi dòng là một tương tác có hướng `u→v` tại thời điểm Unix `t`. Lặp tương
tác ⇒ cạnh song song. Trang nguồn: <https://snap.stanford.edu/data/>.

- **CollegeMsg** — 1.899 đỉnh, 59.835 tin nhắn. Tin nhắn riêng giữa sinh viên ĐH California,
  Irvine. *P. Panzarasa, T. Opsahl, K. M. Carley, JASIST 60(5):911–932 (2009).*
- **email-Eu-core-temporal** — 986 đỉnh, 332.334 email (≈803 ngày). Email nội bộ một cơ sở
  nghiên cứu lớn ở châu Âu; mức trùng lặp rất cao (307.405 cạnh song song).
- **sx-mathoverflow** — 24.818 đỉnh, 506.550 tương tác (trả lời/bình luận) trên MathOverflow.
  Có 116.109 khuyên (tương tác với chính bài của mình).
- **sx-askubuntu** — 159.316 đỉnh, 964.437 tương tác trên Ask Ubuntu (Stack Exchange).
- **sx-superuser** — 194.085 đỉnh, 1.443.339 tương tác trên Super User (Stack Exchange).

**Kèm theo email-Eu-core-temporal: nhãn phòng ban** (thêm 2026-10-19, cùng thư mục):
`email-Eu-core-department-labels.txt.gz` (2.663 byte, tệp nén gốc) và bản giải nén `email-Eu-core-department-labels.txt`
(6.537 byte). Mỗi dòng `người phòng_ban`; theo trang nguồn mỗi người thuộc đúng một trong 42 phòng ban.
Nguồn: <https://snap.stanford.edu/data/email-Eu-core.html>, tệp `https://snap.stanford.edu/data/email-Eu-core-department-labels.txt.gz`
(tải lại ngày 2026-10-19 trùng từng byte). *H. Yin, A. R. Benson, J. Leskovec, D. F. Gleich, "Local Higher-order Graph
Clustering", KDD 2017; J. Leskovec, J. Kleinberg, C. Faloutsos, ACM TKDD 1(1), 2007.* **Giấy phép chưa xác minh**: trang
SNAP không ghi giấy phép. Nhãn là phòng ban thật của từng người (**đo được**, ẩn danh bằng số).
Số đo (2026-10-19): 1.005 dòng, mã người 0–1.004, 42 phòng ban (mã 0–41); cỡ phòng ban nhỏ nhất 1, trung vị 15, lớn nhất
109 người. Nhãn phủ **986/986** đỉnh của `email-Eu-core-temporal.txt`; 19 người có nhãn nhưng không có email nào trong bản
theo thời gian (bản tĩnh email-Eu-core có 1.005 đỉnh).
**Dự án dùng:** WeLT. Bản đã dựng `datasets/email_eu.lg` trong kho dự án, kịch bản `datasets/convert_email.py`: đếm số
email giữa hai người làm trọng số, giữ cặp có từ 25 email trở lên, nhãn đỉnh là phòng ban. Trong kho dự án hai tệp gốc mang
tên `datasets/raw/email/email.txt` (trùng từng byte với `email-Eu-core-temporal.txt`) và `datasets/raw/email/dept.txt`.

*3 tập email-Eu/sx-\*: A. Paranjape, A. R. Benson, J. Leskovec, "Motifs in Temporal Networks",
WSDM 2017.* Các tập sx-\* gộp 3 loại tương tác: trả-lời-câu-hỏi (a2q), bình-luận-câu-hỏi (c2q),
bình-luận-câu-trả-lời (c2a).

## B. Mạng sinh học (connectome) — đa đồ thị không theo thời gian

- **bn-fly-drosophila** — 1.781 đỉnh, 33.641 cạnh trên 9.735 cặp đỉnh duy nhất ⇒ **23.906 cạnh
  song song**, 133 khuyên. Connectome vùng *medulla* trong não ruồi giấm *Drosophila*; đỉnh =
  nơ-ron, cạnh có hướng = một kết nối synapse. **Số cạnh song song giữa hai nơ-ron = số synapse**
  (cường độ kết nối). Định dạng `.edges` (`u v`).
  Nguồn: Network Repository <https://networkrepository.com/bn-fly-drosophila-medulla-1.php>.
  *Dữ liệu gốc: S. Takemura et al., Nature 500:175–181 (2013). Kho: R. A. Rossi & N. K. Ahmed,
  AAAI 2015.*

---

### Gợi ý sử dụng

Nạp bằng **`nx.MultiDiGraph`** để **giữ cạnh song song** (nếu dùng `nx.DiGraph` các cạnh trùng
sẽ bị gộp, mất thông tin đa đồ thị). Ứng dụng: phân tích **temporal motif**, dự đoán tương tác
theo thời gian, phát hiện bùng nổ hoạt động, mô hình lan truyền; với connectome: phân tích
cường độ kết nối, motif mạch thần kinh.

```python
import networkx as nx
G = nx.MultiDiGraph()
with open("02_da_do_thi/CollegeMsg/CollegeMsg.txt") as f:
    for line in f:
        u, v, t = line.split()
        G.add_edge(u, v, time=int(t))
# G.number_of_edges() giữ nguyên cạnh song song
```

---

## C. Bản đã chuẩn hoá — `formated/`

`formated/` chứa **mỗi dataset đúng một file** ở định dạng edge-list mà artifact
(`topk.husm.mg.graph.MgGraphLoader`) đọc được:

```
src dst label1[,label2,...]
```

| File | Nguồn | Đỉnh | Cặp | Cạnh | Type |
|---|---|---:|---:|---:|---:|
| `college-msg.txt` | CollegeMsg | 1.899 | 13.838 | 36.821 | 10 |
| `email-eu-core-temporal.txt` | email-Eu-core-temporal | 986 | 16.064 | 90.429 | 8 |
| `sx-askubuntu.txt` | sx-askubuntu | 159.316 | 455.691 | 682.747 | 10 |
| `sx-mathoverflow.txt` | sx-mathoverflow | 24.818 | 187.986 | 349.507 | 10 |
| `sx-superuser.txt` | sx-superuser | 194.085 | 714.570 | 1.065.009 | 10 |
| `bn-fly-drosophila.txt` | bn-fly-drosophila | 1.781 | 8.911 | 33.508 | 1 |
| `dblp-multigraph.txt` | DBLP-Mappedgraph/multigraph | 83.901 | 141.471 | 159.302 | 50 |

Sinh lại bằng `python3 benchmark/pre_processing/convert_all.py`; chi tiết quy ước
chuyển đổi, cap bội số và cách dùng xem `benchmark/pre_processing/README.md`.

Lưu ý: nhãn đỉnh (phòng ban của email-Eu, node label của DBLP) **không** có chỗ
trong định dạng này — artifact tự sinh nhãn đỉnh bằng `--label-mode degree|random`.
