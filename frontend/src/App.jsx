import React, { useEffect, useState } from "react";

const defaultDoc = {
  title: "",
  memo: "",
  tags: "",
  status: "todo",
  isPinned: false,
  file: null
};

const defaultLink = {
  url: "",
  title: "",
  memo: "",
  tags: "",
  status: "todo",
  isPinned: false
};

const defaultSearch = {
  q: "",
  mode: "keyword",
  resourceType: "",
  domain: "",
  status: "",
  tags: "",
  sort: "relevance",
  pageSize: 20,
  debug: false
};

const emptySearchResponse = {
  items: [],
  page: 0,
  pageSize: 20,
  total: 0,
  totalPages: 0,
  debug: null
};

export default function App() {
  const [workspace, setWorkspace] = useState("upload");

  const [tab, setTab] = useState("document");
  const [doc, setDoc] = useState(defaultDoc);
  const [link, setLink] = useState(defaultLink);
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [recent, setRecent] = useState([]);
  const [recentLoading, setRecentLoading] = useState(false);
  const [selected, setSelected] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [docDetailsOpen, setDocDetailsOpen] = useState(false);
  const [linkDetailsOpen, setLinkDetailsOpen] = useState(false);

  const [search, setSearch] = useState(defaultSearch);
  const [searchResult, setSearchResult] = useState(emptySearchResponse);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchError, setSearchError] = useState(null);

  const statusLabel = (value) => {
    switch (value) {
      case "queued":
        return "대기";
      case "processing":
        return "처리중";
      case "done":
        return "완료";
      case "failed":
        return "실패";
      case "cancelled":
        return "취소됨";
      case "reused":
        return "기존 문서 재사용";
      case "todo":
        return "할 일";
      case "in_progress":
        return "진행 중";
      default:
        return value || "-";
    }
  };

  const stageLabel = (value) => {
    switch (value) {
      case "extracting":
        return "추출";
      case "embedding":
        return "임베딩";
      case "indexing":
        return "인덱싱";
      case "done":
        return "완료";
      case "cancelled":
        return "취소";
      case "timeout":
        return "시간초과";
      default:
        return value || "-";
    }
  };

  const modeLabel = (value) => {
    switch (value) {
      case "semantic":
        return "시맨틱";
      case "hybrid":
        return "하이브리드";
      default:
        return "키워드";
    }
  };

  const progressPercent = (processed, total) => {
    if (!total || total <= 0) return 0;
    return Math.min(100, Math.round((processed / total) * 100));
  };

  const formatScore = (value) => {
    if (value === null || value === undefined) return "-";
    if (Number.isNaN(value)) return "-";
    return Number(value).toFixed(4);
  };

  const fetchRecent = async () => {
    setRecentLoading(true);
    try {
      const res = await fetch("/api/ingest/recent?limit=20");
      const data = await res.json();
      if (!res.ok) throw new Error(data?.message || "목록 조회 실패");
      setRecent(data);
    } catch (err) {
      setRecent([]);
    } finally {
      setRecentLoading(false);
    }
  };

  useEffect(() => {
    fetchRecent();
    const timer = setInterval(fetchRecent, 5000);
    return () => clearInterval(timer);
  }, []);

  const fetchDetail = async (resourceId) => {
    setDetailLoading(true);
    try {
      const res = await fetch(`/api/ingest/${resourceId}`);
      const data = await res.json();
      if (!res.ok) throw new Error(data?.message || "상세 조회 실패");
      setSelected(data);
    } catch (err) {
      setSelected({ error: err.message });
    } finally {
      setDetailLoading(false);
    }
  };

  const cancelIngest = async (resourceId) => {
    setDetailLoading(true);
    try {
      const res = await fetch(`/api/ingest/${resourceId}/cancel`, {
        method: "POST"
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.message || "취소 실패");
      setSelected(data);
      fetchRecent();
    } catch (err) {
      setSelected({ error: err.message });
    } finally {
      setDetailLoading(false);
    }
  };

  const retryIngest = async (resourceId) => {
    setDetailLoading(true);
    try {
      const res = await fetch(`/api/ingest/${resourceId}/retry`, {
        method: "POST"
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.message || "재시도 실패");
      setSelected(data);
      fetchRecent();
    } catch (err) {
      setSelected({ error: err.message });
    } finally {
      setDetailLoading(false);
    }
  };

  const deleteResource = async (resourceId) => {
    const ok = window.confirm("정말 삭제할까요? (DB/파일/검색 인덱스에서 제거됩니다)");
    if (!ok) return;
    setDetailLoading(true);
    try {
      const res = await fetch(`/api/resources/${resourceId}`, {
        method: "DELETE"
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data?.message || "삭제 실패");
      }
      setSelected(null);
      fetchRecent();
    } catch (err) {
      setSelected({ error: err.message });
    } finally {
      setDetailLoading(false);
    }
  };

  const onDocChange = (key) => (e) => {
    const value = key === "isPinned" ? e.target.checked : e.target.value;
    setDoc((prev) => ({ ...prev, [key]: value }));
  };

  const onDocFile = (e) => {
    const file = e.target.files?.[0] || null;
    setDoc((prev) => ({ ...prev, file }));
  };

  const onLinkChange = (key) => (e) => {
    const value = key === "isPinned" ? e.target.checked : e.target.value;
    setLink((prev) => ({ ...prev, [key]: value }));
  };

  const submitDocument = async (e) => {
    e.preventDefault();
    if (!doc.file) {
      setResult({ error: "파일을 선택해 주세요." });
      return;
    }

    const form = new FormData();
    form.append("file", doc.file);
    if (doc.title) form.append("title", doc.title);
    if (doc.memo) form.append("memo", doc.memo);
    if (doc.tags) form.append("tags", doc.tags);
    if (doc.status) form.append("status", doc.status);
    form.append("isPinned", String(doc.isPinned));

    setLoading(true);
    setResult(null);
    try {
      const res = await fetch("/api/upload/document", {
        method: "POST",
        body: form
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.message || "업로드 실패");
      setResult(data);
      setDoc(defaultDoc);
      fetchRecent();
    } catch (err) {
      setResult({ error: err.message });
    } finally {
      setLoading(false);
    }
  };

  const submitLink = async (e) => {
    e.preventDefault();
    if (!link.url) {
      setResult({ error: "URL을 입력해 주세요." });
      return;
    }

    const payload = {
      url: link.url,
      title: link.title,
      memo: link.memo,
      status: link.status,
      isPinned: link.isPinned,
      tags: link.tags
        ? link.tags.split(",").map((t) => t.trim()).filter(Boolean)
        : []
    };

    setLoading(true);
    setResult(null);
    try {
      const res = await fetch("/api/upload/link", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.message || "업로드 실패");
      setResult(data);
      setLink(defaultLink);
      fetchRecent();
    } catch (err) {
      setResult({ error: err.message });
    } finally {
      setLoading(false);
    }
  };

  const onSearchChange = (key) => (e) => {
    const value = key === "debug" ? e.target.checked : e.target.value;
    setSearch((prev) => ({ ...prev, [key]: value }));
  };

  const runSearch = async (targetPage = 0) => {
    if (!search.q.trim()) {
      setSearchError("검색어를 입력해 주세요.");
      return;
    }

    const params = new URLSearchParams();
    params.set("q", search.q);
    params.set("mode", search.mode);
    params.set("page", String(targetPage));
    params.set("pageSize", String(search.pageSize));
    params.set("sort", search.sort);
    params.set("debug", String(search.debug));
    params.set("log", "true");

    if (search.resourceType) params.set("resourceType", search.resourceType);
    if (search.domain) params.set("domain", search.domain);
    if (search.status) params.set("status", search.status);
    if (search.tags) params.set("tags", search.tags);

    setSearchLoading(true);
    setSearchError(null);
    try {
      const res = await fetch(`/api/search?${params.toString()}`);
      const data = await res.json();
      if (!res.ok) throw new Error(data?.message || "검색 실패");
      setSearchResult(data);
    } catch (err) {
      setSearchError(err.message);
      setSearchResult(emptySearchResponse);
    } finally {
      setSearchLoading(false);
    }
  };

  const submitSearch = async (e) => {
    e.preventDefault();
    await runSearch(0);
  };

  const moveSearchPage = async (delta) => {
    const nextPage = searchResult.page + delta;
    if (nextPage < 0) return;
    if (nextPage >= searchResult.totalPages) return;
    await runSearch(nextPage);
  };

  const openDocumentViewer = (item) => {
    const hash = new URLSearchParams();
    if (typeof item.bestPageIndex === "number" && item.bestPageIndex >= 0) {
      hash.set("page", String(item.bestPageIndex + 1));
    }
    if (search.q?.trim()) {
      hash.set("search", search.q.trim());
    }

    const baseUrl = `/api/resources/${item.resourceId}/file`;
    const target = hash.toString() ? `${baseUrl}#${hash.toString()}` : baseUrl;
    window.open(target, "_blank", "noopener,noreferrer");
  };

  const openLinkSource = (item) => {
    if (!item?.url) return;
    window.open(item.url, "_blank", "noopener,noreferrer");
  };

  return (
    <div className="page playful">
      <header className="hero">
        <div className="hero-copy">
          <span className="pill">Collecta Workbench</span>
          <h1>{workspace === "upload" ? "자료 업로드" : "검색 워크벤치"}</h1>
          <p>
            {workspace === "upload"
              ? "문서와 링크를 등록하고 진행 상태를 확인하세요."
              : "키워드, 시맨틱, 하이브리드 검색을 비교하면서 결과를 점검하세요."}
          </p>
        </div>
        <div className="hero-tabs workspace-tabs">
          <button
            className={workspace === "upload" ? "active" : ""}
            onClick={() => setWorkspace("upload")}
          >
            업로드
          </button>
          <button
            className={workspace === "search" ? "active" : ""}
            onClick={() => setWorkspace("search")}
          >
            검색
          </button>
        </div>
      </header>

      {workspace === "upload" ? (
        <section className="grid">
          <div className="stack">
            <div className="hero-tabs upload-tabs">
              <button
                className={tab === "document" ? "active" : ""}
                onClick={() => setTab("document")}
              >
                문서
              </button>
              <button
                className={tab === "link" ? "active" : ""}
                onClick={() => setTab("link")}
              >
                링크
              </button>
            </div>

            {tab === "document" && (
              <form className="card playful-card" onSubmit={submitDocument}>
                <div className="card-title">
                  <span>문서 정보</span>
                </div>
                <div className="row">
                  <label>파일</label>
                  <input type="file" onChange={onDocFile} />
                  <p className="helper">가장 먼저 파일을 선택하세요. 나머지는 선택 사항입니다.</p>
                </div>
                <button
                  type="button"
                  className="ghost"
                  onClick={() => setDocDetailsOpen((v) => !v)}
                >
                  {docDetailsOpen ? "추가 정보 접기" : "추가 정보 열기"}
                </button>
                {docDetailsOpen && (
                  <div className="accordion">
                    <div className="row">
                      <label>제목</label>
                      <input value={doc.title} onChange={onDocChange("title")} />
                    </div>
                    <div className="row">
                      <label>메모</label>
                      <textarea value={doc.memo} onChange={onDocChange("memo")} />
                    </div>
                    <div className="row">
                      <label>태그 (쉼표 구분)</label>
                      <input value={doc.tags} onChange={onDocChange("tags")} />
                    </div>
                    <div className="row inline">
                      <label>상태</label>
                      <select value={doc.status} onChange={onDocChange("status")}>
                        <option value="todo">할 일</option>
                        <option value="in_progress">진행 중</option>
                        <option value="done">완료</option>
                      </select>
                      <label className="checkbox">
                        <input
                          type="checkbox"
                          checked={doc.isPinned}
                          onChange={onDocChange("isPinned")}
                        />
                        중요 표시
                      </label>
                    </div>
                  </div>
                )}
                <button type="submit" disabled={loading}>
                  {loading ? "업로드 중..." : "문서 업로드"}
                </button>
              </form>
            )}

            {tab === "link" && (
              <form className="card playful-card" onSubmit={submitLink}>
                <div className="card-title">
                  <span>링크 정보</span>
                </div>
                <div className="row">
                  <label>URL</label>
                  <input value={link.url} onChange={onLinkChange("url")} />
                  <p className="helper">URL만 입력하면 바로 저장됩니다.</p>
                </div>
                <button
                  type="button"
                  className="ghost"
                  onClick={() => setLinkDetailsOpen((v) => !v)}
                >
                  {linkDetailsOpen ? "추가 정보 접기" : "추가 정보 열기"}
                </button>
                {linkDetailsOpen && (
                  <div className="accordion">
                    <div className="row">
                      <label>제목</label>
                      <input value={link.title} onChange={onLinkChange("title")} />
                    </div>
                    <div className="row">
                      <label>메모</label>
                      <textarea value={link.memo} onChange={onLinkChange("memo")} />
                    </div>
                    <div className="row">
                      <label>태그 (쉼표 구분)</label>
                      <input value={link.tags} onChange={onLinkChange("tags")} />
                    </div>
                    <div className="row inline">
                      <label>상태</label>
                      <select value={link.status} onChange={onLinkChange("status")}>
                        <option value="todo">할 일</option>
                        <option value="in_progress">진행 중</option>
                        <option value="done">완료</option>
                      </select>
                      <label className="checkbox">
                        <input
                          type="checkbox"
                          checked={link.isPinned}
                          onChange={onLinkChange("isPinned")}
                        />
                        중요 표시
                      </label>
                    </div>
                  </div>
                )}
                <button type="submit" disabled={loading}>
                  {loading ? "추가 중..." : "링크 추가"}
                </button>
              </form>
            )}

            {result && (
              <div className={`result ${result.error ? "error" : ""}`}>
                {result.error ? (
                  <span>에러: {result.error}</span>
                ) : (
                  <span>
                    {result.deduplicated ? "기존 문서 재사용" : "요청 완료"}: resourceId={result.resourceId}
                  </span>
                )}
              </div>
            )}
          </div>

          <section className="card list playful-card">
            <div className="list-header">
              <h2>최근 업로드</h2>
              <div className="actions">
                <span className="hint">자동 갱신 5초</span>
                <button type="button" onClick={fetchRecent} disabled={recentLoading}>
                  {recentLoading ? "갱신 중..." : "새로고침"}
                </button>
              </div>
            </div>
            {recent.length === 0 ? (
              <p className="muted">최근 업로드가 없습니다.</p>
            ) : (
              <ul className="list-items">
                {recent.map((item) => (
                  <li
                    key={item.resourceId}
                    className={`status ${item.status}`}
                    onClick={() => fetchDetail(item.resourceId)}
                    role="button"
                    tabIndex={0}
                  >
                    <div>
                      <strong>{item.title || "(제목 없음)"}</strong>
                      <span className="meta">
                        #{item.resourceId} · {item.resourceType}
                      </span>
                    </div>
                    <div className="right">
                      <span className="badge">{statusLabel(item.status)}</span>
                      {item.stage && (
                        <span className="stage">{stageLabel(item.stage)}</span>
                      )}
                      {item.errorMessage && <span className="error-msg">{item.errorMessage}</span>}
                    </div>
                    {item.totalUnits ? (
                      <div className="progress">
                        <div
                          className="bar"
                          style={{
                            width: `${progressPercent(item.processedUnits || 0, item.totalUnits)}%`
                          }}
                        />
                        <span className="progress-text">
                          {item.processedUnits || 0}/{item.totalUnits}
                        </span>
                      </div>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}

            <div className="detail">
              <h3>상세</h3>
              {detailLoading && <p className="muted">불러오는 중...</p>}
              {!detailLoading && !selected && (
                <p className="muted">항목을 클릭하면 상세를 보여줍니다.</p>
              )}
              {!detailLoading && selected && selected.error && (
                <p className="error-msg">{selected.error}</p>
              )}
              {!detailLoading && selected && !selected.error && (
                <div className="detail-grid">
                  <div>
                    <span className="label">resourceId</span>
                    <span>{selected.resourceId}</span>
                  </div>
                  <div>
                    <span className="label">type</span>
                    <span>{selected.resourceType}</span>
                  </div>
                  <div>
                    <span className="label">title</span>
                    <span>{selected.title || "-"}</span>
                  </div>
                  <div className="full detail-actions">
                    {(selected.status === "queued" || selected.status === "processing") && (
                      <button
                        type="button"
                        className="ghost"
                        onClick={() => cancelIngest(selected.resourceId)}
                        disabled={detailLoading}
                      >
                        업로드 중단
                      </button>
                    )}
                    {(selected.status === "failed" || selected.status === "cancelled") && (
                      <button
                        type="button"
                        className="ghost"
                        onClick={() => retryIngest(selected.resourceId)}
                        disabled={detailLoading}
                      >
                        재시도
                      </button>
                    )}
                    <button
                      type="button"
                      className="danger"
                      onClick={() => deleteResource(selected.resourceId)}
                      disabled={detailLoading}
                    >
                      삭제
                    </button>
                  </div>
                  <div>
                    <span className="label">status</span>
                    <span>{statusLabel(selected.status)}</span>
                  </div>
                  <div>
                    <span className="label">stage</span>
                    <span>{stageLabel(selected.stage)}</span>
                  </div>
                  <div>
                    <span className="label">updatedAt</span>
                    <span>{selected.updatedAt}</span>
                  </div>
                  <div className="full">
                    <span className="label">progress</span>
                    {selected.totalUnits ? (
                      <div className="progress detail-progress">
                        <div
                          className="bar"
                          style={{
                            width: `${progressPercent(selected.processedUnits || 0, selected.totalUnits)}%`
                          }}
                        />
                        <span className="progress-text">
                          {selected.processedUnits || 0}/{selected.totalUnits}
                        </span>
                      </div>
                    ) : (
                      <span>-</span>
                    )}
                  </div>
                  <div className="full">
                    <span className="label">error</span>
                    <span className="error-msg">{selected.errorMessage || "-"}</span>
                  </div>
                </div>
              )}
            </div>
          </section>
        </section>
      ) : (
        <section className="grid search-grid">
          <form className="card playful-card" onSubmit={submitSearch}>
            <div className="card-title">검색 조건</div>
            <div className="row">
              <label>검색어</label>
              <input value={search.q} onChange={onSearchChange("q")} placeholder="예: 벡터 검색 확인" />
            </div>

            <div className="row inline wrap">
              <div className="row compact">
                <label>모드</label>
                <select value={search.mode} onChange={onSearchChange("mode")}>
                  <option value="keyword">키워드</option>
                  <option value="semantic">시맨틱</option>
                  <option value="hybrid">하이브리드</option>
                </select>
              </div>
              <div className="row compact">
                <label>정렬</label>
                <select value={search.sort} onChange={onSearchChange("sort")}>
                  <option value="relevance">관련도</option>
                  <option value="newest">최신순</option>
                  <option value="pinned">고정 우선</option>
                </select>
              </div>
              <div className="row compact">
                <label>페이지 크기</label>
                <select value={search.pageSize} onChange={onSearchChange("pageSize")}>
                  <option value="10">10</option>
                  <option value="20">20</option>
                  <option value="50">50</option>
                </select>
              </div>
              <label className="checkbox debug-check">
                <input
                  type="checkbox"
                  checked={search.debug}
                  onChange={onSearchChange("debug")}
                />
                디버그 보기
              </label>
            </div>

            <div className="row inline wrap">
              <div className="row compact grow">
                <label>리소스 타입</label>
                <select value={search.resourceType} onChange={onSearchChange("resourceType")}>
                  <option value="">전체</option>
                  <option value="document">문서</option>
                  <option value="link">링크</option>
                </select>
              </div>
              <div className="row compact grow">
                <label>도메인</label>
                <input value={search.domain} onChange={onSearchChange("domain")} placeholder="example.com" />
              </div>
              <div className="row compact grow">
                <label>상태</label>
                <select value={search.status} onChange={onSearchChange("status")}>
                  <option value="">전체</option>
                  <option value="todo">할 일</option>
                  <option value="in_progress">진행 중</option>
                  <option value="done">완료</option>
                </select>
              </div>
              <div className="row compact grow">
                <label>태그(쉼표)</label>
                <input value={search.tags} onChange={onSearchChange("tags")} placeholder="ai,study" />
              </div>
            </div>

            <button type="submit" disabled={searchLoading}>
              {searchLoading ? "검색 중..." : "검색 실행"}
            </button>

            {searchError && <div className="result error">에러: {searchError}</div>}
          </form>

          <section className="card playful-card list search-result-card">
            <div className="list-header">
              <h2>검색 결과</h2>
              <div className="actions">
                <span className="hint">모드: {modeLabel(searchResult?.debug?.mode || search.mode)}</span>
              </div>
            </div>

            <div className="search-meta">
              <span>총 {searchResult.total}건</span>
              <span>
                페이지 {searchResult.total === 0 ? 0 : searchResult.page + 1}/{searchResult.totalPages || 0}
              </span>
              <span>total {searchResult?.debug?.totalMs ?? 0}ms</span>
              {searchResult?.debug?.embedMs !== null && searchResult?.debug?.embedMs !== undefined && (
                <span>embed {searchResult.debug.embedMs}ms</span>
              )}
              {searchResult?.debug?.esMs !== null && searchResult?.debug?.esMs !== undefined && (
                <span>es {searchResult.debug.esMs}ms</span>
              )}
            </div>

            {searchLoading ? (
              <p className="muted">검색 중...</p>
            ) : searchResult.items.length === 0 ? (
              <p className="muted">결과가 없습니다.</p>
            ) : (
              <ul className="search-list">
                {searchResult.items.map((item) => (
                  <li key={item.resourceId} className="search-item">
                    <div className="search-head">
                      <strong>{item.title || "(제목 없음)"}</strong>
                      <span className="badge small">{item.type}</span>
                    </div>
                    <p className="meta">
                      #{item.resourceId} · score {formatScore(item.bestScore)} · match {item.matchCount}
                    </p>
                    {typeof item.bestPageIndex === "number" && item.bestPageIndex >= 0 && (
                      <p className="meta">best page: {item.bestPageIndex + 1}</p>
                    )}
                    {item.tags?.length > 0 && (
                      <p className="meta">tags: {item.tags.join(", ")}</p>
                    )}
                    {item.bestSnippet && (
                      <p
                        className="snippet"
                        dangerouslySetInnerHTML={{ __html: item.bestSnippet }}
                      />
                    )}
                    {searchResult?.debug?.enabled && (
                      <div className="score-row">
                        <span>keyword {formatScore(item.keywordScore)}</span>
                        <span>vector {formatScore(item.vectorScore)}</span>
                        <span>final {formatScore(item.finalScore)}</span>
                      </div>
                    )}
                    <div className="search-actions">
                      {item.type === "document" && (
                        <button
                          type="button"
                          className="ghost"
                          onClick={() => openDocumentViewer(item)}
                        >
                          PDF 열기
                        </button>
                      )}
                      {item.type === "link" && item.url && (
                        <button
                          type="button"
                          className="ghost"
                          onClick={() => openLinkSource(item)}
                        >
                          원문 열기
                        </button>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            )}

            <div className="pager">
              <button
                type="button"
                className="ghost"
                onClick={() => moveSearchPage(-1)}
                disabled={searchLoading || searchResult.page <= 0}
              >
                이전
              </button>
              <button
                type="button"
                className="ghost"
                onClick={() => moveSearchPage(1)}
                disabled={
                  searchLoading ||
                  searchResult.totalPages === 0 ||
                  searchResult.page >= searchResult.totalPages - 1
                }
              >
                다음
              </button>
            </div>
          </section>
        </section>
      )}
    </div>
  );
}
