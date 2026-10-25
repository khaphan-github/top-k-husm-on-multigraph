/**
 * Static Top-K HUSM on an undirected multigraph (external-edge-utility model).
 *
 * <h2>Notation glossary (paper symbol -&gt; code identifier)</h2>
 *
 * <p>The paper writes most quantities with one or two letters. Code never does: every
 * identifier below spells the quantity out, so a reader does not need the paper to follow
 * the implementation.
 *
 * <table border="1">
 *   <caption>Symbols used throughout this package</caption>
 *   <tr><th>Paper</th><th>Meaning</th><th>Code identifier</th></tr>
 *   <tr><td>u(P)</td><td>pattern utility</td><td>{@code utility}</td></tr>
 *   <tr><td>s(P), CMNI</td><td>minimum image support</td><td>{@code support}</td></tr>
 *   <tr><td>m(P)</td><td>total edge multiplicity</td><td>{@code multiplicity}</td></tr>
 *   <tr><td>le</td><td>multigraph edge label (type, multiplicity)*</td><td>{@code edgeLabel}</td></tr>
 *   <tr><td>la, lb</td><td>lower / upper endpoint label</td><td>{@code labelLow}, {@code labelHigh}</td></tr>
 *   <tr><td>i, j</td><td>pattern vertex indices of a DFS-code edge</td>
 *       <td>{@code fromVertex}, {@code toVertex}</td></tr>
 *   <tr><td>li, lj</td><td>labels of those vertices</td>
 *       <td>{@code fromLabel}, {@code toLabel}</td></tr>
 *   <tr><td>phi</td><td>embedding pattern vertex -&gt; graph vertex</td><td>{@code mapping}</td></tr>
 *   <tr><td>D(v)</td><td>current domain (image candidates) of vertex v</td><td>{@code domains}</td></tr>
 *   <tr><td>ESV</td><td>extension space vertices (HUSM Def. 11)</td><td>{@code esv}</td></tr>
 *   <tr><td>theta</td><td>dynamic Top-K threshold</td>
 *       <td>{@code currentThreshold()}, {@code finalThreshold}</td></tr>
 *   <tr><td>IVU / EU / CEU</td><td>pruning bounds</td>
 *       <td>{@code ivuBound}, {@code euBound}, {@code ceuBound}</td></tr>
 * </table>
 *
 * <h2>Package layout</h2>
 *
 * <ul>
 *   <li>{@code graph} - immutable multigraph, adjacency/pair index, MER balls, ESV cache,
 *       dataset loader.</li>
 *   <li>{@code dfs} - DFS code, minimum-code canonicalisation, edge/vector labels.</li>
 *   <li>{@code csp} - exact image sets (CMNI table) via GraMi-style cross-marking.</li>
 *   <li>{@code mining} - utility and bound computation, extension generation, the search.</li>
 *   <li>{@code cli} - option parsing, run pipeline, console/report output, benchmarks.</li>
 *   <li>{@code log} - dependency-free logger and per-step profiler.</li>
 * </ul>
 *
 * <h2>Stable output contract</h2>
 *
 * <p>{@code MgEdgeTuple#toString()} (and therefore {@code MgDFSCode#key()}), the report
 * header lines in {@code MgReport} and the JSON field names of the benchmark entry points
 * are parsed by external tooling; they must not change even when internal names do.
 */
package topk.husm.mg;
