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

export default function App() {
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
      default:
        return value || "-";
    }
  };

  const progressPercent = (processed, total) => {
    if (!total || total <= 0) return 0;
    return Math.min(100, Math.round((processed / total) * 100));
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

  return (
    <div className="page playful">
      <header className="hero">
        <div className="hero-copy">
          <span className="pill">Playful Utility</span>
          <h1>Collecta 업로드</h1>
          <p>오늘의 자료를 빠르게 넣고, 바로 검색에 반영하세요.</p>
        </div>
        <div className="hero-tabs">
          <button
            className={tab === "document" ? "active" : ""}
            onClick={() => setTab("document")}
          >
            📄 문서
          </button>
          <button
            className={tab === "link" ? "active" : ""}
            onClick={() => setTab("link")}
          >
            🔗 링크
          </button>
        </div>
      </header>

      <section className="grid">
        <div className="stack">
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
                <span>요청 완료: resourceId={result.resourceId}</span>
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
    </div>
  );
}
