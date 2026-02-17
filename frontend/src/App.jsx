import React, { useEffect, useRef, useState } from "react";

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

const emptyResourceContent = {
  resourceId: null,
  resourceType: "",
  title: "",
  query: null,
  chunkCount: 0,
  chunks: []
};

const emptyResourceEditForm = {
  resourceId: null,
  type: "document",
  title: "",
  memo: "",
  tags: "",
  status: "todo",
  isPinned: false,
  url: ""
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
  const [uploadingDocs, setUploadingDocs] = useState([]);
  const [uploadingLoading, setUploadingLoading] = useState(false);
  const [uploadingError, setUploadingError] = useState(null);
  const [resourceStatusFilter, setResourceStatusFilter] = useState("active");
  const [resourceTypeFilter, setResourceTypeFilter] = useState("");
  const [resourcePinnedFilter, setResourcePinnedFilter] = useState("all");
  const [resourceFilterOpen, setResourceFilterOpen] = useState(false);
  const [resourceSearchInput, setResourceSearchInput] = useState("");
  const [resourceSearchQuery, setResourceSearchQuery] = useState("");
  const [resourcePage, setResourcePage] = useState(0);
  const [resourceTotal, setResourceTotal] = useState(0);
  const [resourceTotalPages, setResourceTotalPages] = useState(0);
  const [resourceError, setResourceError] = useState(null);
  const [statusMenuResourceId, setStatusMenuResourceId] = useState(null);
  const [statusUpdatingId, setStatusUpdatingId] = useState(null);
  const [resourceEditOpen, setResourceEditOpen] = useState(false);
  const [resourceEditForm, setResourceEditForm] = useState(emptyResourceEditForm);
  const [resourceEditSaving, setResourceEditSaving] = useState(false);
  const [resourceEditError, setResourceEditError] = useState(null);
  const [memoViewer, setMemoViewer] = useState(null);
  const [resourceDetailOpen, setResourceDetailOpen] = useState(false);
  const [selected, setSelected] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [docDetailsOpen, setDocDetailsOpen] = useState(false);
  const [linkDetailsOpen, setLinkDetailsOpen] = useState(false);

  const [search, setSearch] = useState(defaultSearch);
  const [searchResult, setSearchResult] = useState(emptySearchResponse);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchError, setSearchError] = useState(null);
  const [contentTarget, setContentTarget] = useState(null);
  const [contentResult, setContentResult] = useState(emptyResourceContent);
  const [contentLoading, setContentLoading] = useState(false);
  const [contentError, setContentError] = useState(null);
  const [contentTab, setContentTab] = useState("matched");
  const [resourcePageIndexBaseMap, setResourcePageIndexBaseMap] = useState({});
  const resourceFilterRef = useRef(null);

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

  const resourceStatusFilterLabel = (value) => {
    switch (value) {
      case "active":
        return "할 일/진행중";
      case "todo":
        return "할 일";
      case "in_progress":
        return "진행중";
      case "done":
        return "완료";
      default:
        return "전체 자료";
    }
  };

  const resourceTypeFilterLabel = (value) => {
    switch (value) {
      case "document":
        return "문서";
      case "link":
        return "링크";
      default:
        return "전체 타입";
    }
  };

  const resourcePinnedFilterLabel = (value) => {
    switch (value) {
      case "pinned":
        return "중요만";
      default:
        return "전체";
    }
  };

  const resourceStatusesParam = (value) => {
    if (value === "active") return "todo,in_progress";
    if (value === "all") return "";
    return value || "";
  };

  const normalizeResourceSearchItems = (items) =>
    (items || []).map((item) => ({
      ...item,
      type: item?.type || item?.resourceType || "-",
      status: item?.status || "",
      tags: Array.isArray(item?.tags) ? item.tags : []
    }));

  const resourceSearchActive = resourceSearchQuery.trim().length > 0;

  const parseTagsInput = (raw) =>
    (raw || "")
      .split(",")
      .map((tag) => tag.trim())
      .filter(Boolean);

  const toEditForm = (item) => ({
    resourceId: item?.resourceId ?? null,
    type: item?.type || item?.resourceType || "document",
    title: item?.title || "",
    memo: item?.memo ?? "",
    tags: Array.isArray(item?.tags) ? item.tags.join(", ") : "",
    status: item?.status || "todo",
    isPinned: Boolean(item?.isPinned),
    url: item?.url || ""
  });

  const applyResourceResponseToItem = (item, payload) => {
    if (!payload) return item;
    const next = { ...item };
    if (typeof payload.resourceType === "string" && payload.resourceType) {
      next.type = payload.resourceType;
    }
    if (typeof payload.title === "string") {
      next.title = payload.title;
    }
    if (Object.prototype.hasOwnProperty.call(payload, "memo")) {
      next.memo = payload.memo;
    }
    if (Array.isArray(payload.tags)) {
      next.tags = payload.tags;
    }
    if (typeof payload.status === "string" && payload.status) {
      next.status = payload.status;
    }
    if (typeof payload.isPinned === "boolean") {
      next.isPinned = payload.isPinned;
    }
    if (Object.prototype.hasOwnProperty.call(payload, "url")) {
      next.url = payload.url;
    }
    return next;
  };

  const mergeUpdatedResource = (resourceId, payload) => {
    setRecent((prev) =>
      (prev || []).map((item) =>
        item.resourceId === resourceId ? applyResourceResponseToItem(item, payload) : item
      )
    );
  };

  const detectPageIndexBase = (chunks) => {
    const indices = (chunks || [])
      .map((chunk) => chunk?.pageIndex)
      .filter((v) => Number.isInteger(v) && v >= 0);

    if (indices.length === 0) return 0;
    const minPageIndex = Math.min(...indices);
    return minPageIndex >= 1 ? 1 : 0;
  };

  const toPdfPageNumber = (pageIndex, pageIndexBase = 0) => {
    if (!Number.isInteger(pageIndex) || pageIndex < 0) return null;
    if (pageIndexBase === 1) return pageIndex;
    return pageIndex + 1;
  };

  const toDisplayPageNumber = (pageIndex, pageIndexBase = 0) => {
    const pageNumber = toPdfPageNumber(pageIndex, pageIndexBase);
    if (!Number.isInteger(pageNumber) || pageNumber < 1) return null;
    return pageNumber;
  };

  const fetchRecent = async (
    targetPage = resourcePage,
    targetStatus = resourceStatusFilter,
    targetType = resourceTypeFilter,
    targetPinned = resourcePinnedFilter,
    targetQuery = resourceSearchQuery
  ) => {
    const safePage = Math.max(0, targetPage);
    const safeQuery = (targetQuery || "").trim();
    setRecentLoading(true);
    setResourceError(null);

    const statusCsv = resourceStatusesParam(targetStatus);
    const isPinnedParam = targetPinned === "pinned" ? "true" : "";

    try {
      let endpoint = "/api/resources";
      const params = new URLSearchParams();
      params.set("page", String(safePage));
      params.set("pageSize", "20");
      if (statusCsv) params.set("statuses", statusCsv);
      if (targetType) params.set("resourceType", targetType);
      if (isPinnedParam) params.set("isPinned", isPinnedParam);

      if (safeQuery) {
        endpoint = "/api/search";
        params.set("q", safeQuery);
        params.set("mode", "hybrid");
        params.set("sort", "relevance");
        params.set("debug", "false");
        params.set("log", "false");
      }

      const res = await fetch(`${endpoint}?${params.toString()}`);
      const data = await res.json();
      if (!res.ok) throw new Error(data?.message || "목록 조회 실패");
      setRecent(safeQuery ? normalizeResourceSearchItems(data?.items) : (data?.items || []));
      setResourcePage(data?.page ?? safePage);
      setResourceTotal(data?.total ?? 0);
      setResourceTotalPages(data?.totalPages ?? 0);
    } catch (err) {
      setRecent([]);
      setResourceTotal(0);
      setResourceTotalPages(0);
      setResourceError(err.message);
    } finally {
      setRecentLoading(false);
    }
  };

  const fetchUploadingDocs = async () => {
    setUploadingLoading(true);
    setUploadingError(null);
    try {
      const res = await fetch("/api/ingest/recent?limit=50");
      const data = await res.json();
      if (!res.ok) throw new Error(data?.message || "업로드 목록 조회 실패");

      const activeDocs = (data || []).filter(
        (item) =>
          item?.resourceType === "document" &&
          (item?.status === "queued" || item?.status === "processing")
      );
      setUploadingDocs(activeDocs);
    } catch (err) {
      setUploadingDocs([]);
      setUploadingError(err.message);
    } finally {
      setUploadingLoading(false);
    }
  };

  useEffect(() => {
    if (workspace !== "resources") return undefined;

    fetchRecent(
      resourcePage,
      resourceStatusFilter,
      resourceTypeFilter,
      resourcePinnedFilter,
      resourceSearchQuery
    );
    if (resourceSearchActive) return undefined;

    const timer = setInterval(() => {
      fetchRecent(
        resourcePage,
        resourceStatusFilter,
        resourceTypeFilter,
        resourcePinnedFilter,
        resourceSearchQuery
      );
    }, 5000);
    return () => clearInterval(timer);
  }, [workspace, resourcePage, resourceStatusFilter, resourceTypeFilter, resourcePinnedFilter, resourceSearchQuery]);

  useEffect(() => {
    if (workspace !== "upload") return undefined;

    fetchUploadingDocs();
    const timer = setInterval(() => {
      fetchUploadingDocs();
    }, 5000);
    return () => clearInterval(timer);
  }, [workspace]);

  useEffect(() => {
    if (!resourceDetailOpen) return;
    const onKeyDown = (event) => {
      if (event.key !== "Escape") return;
      closeResourceDetail();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [resourceDetailOpen]);

  useEffect(() => {
    if (!resourceFilterOpen) return undefined;
    const onMouseDown = (event) => {
      if (resourceFilterRef.current?.contains(event.target)) return;
      setResourceFilterOpen(false);
    };
    const onKeyDown = (event) => {
      if (event.key !== "Escape") return;
      setResourceFilterOpen(false);
    };
    window.addEventListener("mousedown", onMouseDown);
    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.removeEventListener("mousedown", onMouseDown);
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [resourceFilterOpen]);

  useEffect(() => {
    if (statusMenuResourceId === null) return undefined;
    const onMouseDown = (event) => {
      if (!(event.target instanceof Element)) return;
      if (event.target.closest(".status-dot-menu-wrap")) return;
      setStatusMenuResourceId(null);
    };
    const onKeyDown = (event) => {
      if (event.key !== "Escape") return;
      setStatusMenuResourceId(null);
    };
    window.addEventListener("mousedown", onMouseDown);
    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.removeEventListener("mousedown", onMouseDown);
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [statusMenuResourceId]);

  useEffect(() => {
    if (!resourceEditOpen) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== "Escape" || resourceEditSaving) return;
      setResourceEditOpen(false);
      setResourceEditForm(emptyResourceEditForm);
      setResourceEditError(null);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [resourceEditOpen, resourceEditSaving]);

  useEffect(() => {
    if (!memoViewer) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== "Escape") return;
      setMemoViewer(null);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [memoViewer]);

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

  const openResourceDetail = (resourceId) => {
    setResourceDetailOpen(true);
    setSelected(null);
    setStatusMenuResourceId(null);
    fetchDetail(resourceId);
  };

  const closeResourceDetail = () => {
    setResourceDetailOpen(false);
    setSelected(null);
    setStatusMenuResourceId(null);
  };

  const openResourceEdit = (item) => {
    setResourceEditForm(toEditForm(item));
    setResourceEditError(null);
    setResourceEditOpen(true);
    setStatusMenuResourceId(null);
  };

  const closeResourceEdit = () => {
    if (resourceEditSaving) return;
    setResourceEditOpen(false);
    setResourceEditForm(emptyResourceEditForm);
    setResourceEditError(null);
  };

  const requestResourcePatch = async (resourceId, payload, fallbackMessage) => {
    const res = await fetch(`/api/resources/${resourceId}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data?.message || fallbackMessage);
    }
    return data;
  };

  const saveResourceEdit = async (event) => {
    event.preventDefault();
    if (!resourceEditForm?.resourceId) return;

    const safeTitle = resourceEditForm.title.trim();
    if (!safeTitle) {
      setResourceEditError("제목을 입력해 주세요.");
      return;
    }

    if (resourceEditForm.type === "link" && !resourceEditForm.url.trim()) {
      setResourceEditError("URL을 입력해 주세요.");
      return;
    }

    setResourceEditSaving(true);
    setResourceEditError(null);
    setResourceError(null);

    const payload = {
      title: safeTitle,
      memo: resourceEditForm.memo,
      tags: parseTagsInput(resourceEditForm.tags),
      status: resourceEditForm.status,
      isPinned: resourceEditForm.isPinned
    };
    if (resourceEditForm.type === "link") {
      payload.url = resourceEditForm.url.trim();
    }

    try {
      const data = await requestResourcePatch(
        resourceEditForm.resourceId,
        payload,
        "수정 저장 실패"
      );
      mergeUpdatedResource(resourceEditForm.resourceId, data);
      setResourceEditOpen(false);
      setResourceEditForm(emptyResourceEditForm);
      setStatusMenuResourceId(null);

      fetchRecent(
        resourcePage,
        resourceStatusFilter,
        resourceTypeFilter,
        resourcePinnedFilter,
        resourceSearchQuery
      );
    } catch (err) {
      setResourceEditError(err.message);
    } finally {
      setResourceEditSaving(false);
    }
  };

  const cancelIngest = async (resourceId) => {
    const ok = window.confirm("이 업로드를 중단할까요?");
    if (!ok) return;

    setDetailLoading(true);
    try {
      const res = await fetch(`/api/ingest/${resourceId}/cancel`, {
        method: "POST"
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.message || "취소 실패");
      setSelected(data);
      fetchRecent();
      fetchUploadingDocs();
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
      fetchUploadingDocs();
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
      setResourceDetailOpen(false);
      fetchRecent();
      fetchUploadingDocs();
    } catch (err) {
      setSelected({ error: err.message });
    } finally {
      setDetailLoading(false);
    }
  };

  const updateResourceStatus = async (resourceId, nextStatus) => {
    if (statusUpdatingId === resourceId) return;
    setStatusUpdatingId(resourceId);
    setResourceError(null);
    try {
      const data = await requestResourcePatch(
        resourceId,
        { status: nextStatus },
        "상태 변경 실패"
      );
      mergeUpdatedResource(resourceId, data);
      setStatusMenuResourceId(null);

      fetchRecent(
        resourcePage,
        resourceStatusFilter,
        resourceTypeFilter,
        resourcePinnedFilter,
        resourceSearchQuery
      );
    } catch (err) {
      setResourceError(err.message);
    } finally {
      setStatusUpdatingId(null);
    }
  };

  const updateResourcePinned = async (resourceId, nextPinned) => {
    if (statusUpdatingId === resourceId) return;
    setStatusUpdatingId(resourceId);
    setResourceError(null);
    try {
      const data = await requestResourcePatch(
        resourceId,
        { isPinned: nextPinned },
        "중요 표시 변경 실패"
      );
      mergeUpdatedResource(resourceId, data);
      setStatusMenuResourceId(null);
      fetchRecent(
        resourcePage,
        resourceStatusFilter,
        resourceTypeFilter,
        resourcePinnedFilter,
        resourceSearchQuery
      );
    } catch (err) {
      setResourceError(err.message);
    } finally {
      setStatusUpdatingId(null);
    }
  };

  const switchResourceStatusFilter = (nextStatus) => {
    if (nextStatus === resourceStatusFilter) return;
    setResourceStatusFilter(nextStatus);
    setResourcePage(0);
    setSelected(null);
    setResourceDetailOpen(false);
    setResourceEditOpen(false);
    setResourceEditForm(emptyResourceEditForm);
    setResourceEditError(null);
    setResourceFilterOpen(false);
    setStatusMenuResourceId(null);
  };

  const switchResourceTypeFilter = (nextType) => {
    if (nextType === resourceTypeFilter) return;
    setResourceTypeFilter(nextType);
    setResourcePage(0);
    setSelected(null);
    setResourceDetailOpen(false);
    setResourceEditOpen(false);
    setResourceEditForm(emptyResourceEditForm);
    setResourceEditError(null);
    setResourceFilterOpen(false);
    setStatusMenuResourceId(null);
  };

  const switchResourcePinnedFilter = (nextPinned) => {
    if (nextPinned === resourcePinnedFilter) return;
    setResourcePinnedFilter(nextPinned);
    setResourcePage(0);
    setSelected(null);
    setResourceDetailOpen(false);
    setResourceEditOpen(false);
    setResourceEditForm(emptyResourceEditForm);
    setResourceEditError(null);
    setResourceFilterOpen(false);
    setStatusMenuResourceId(null);
  };

  const submitResourceSearch = (event) => {
    event.preventDefault();
    const nextQuery = resourceSearchInput.trim();
    if (nextQuery === resourceSearchQuery && resourcePage === 0) return;
    setResourceSearchQuery(nextQuery);
    setResourcePage(0);
    setSelected(null);
    setResourceDetailOpen(false);
    setResourceEditOpen(false);
    setResourceEditForm(emptyResourceEditForm);
    setResourceEditError(null);
    setStatusMenuResourceId(null);
  };

  const clearResourceSearch = () => {
    if (!resourceSearchInput && !resourceSearchQuery) return;
    setResourceSearchInput("");
    setResourceSearchQuery("");
    setResourcePage(0);
    setSelected(null);
    setResourceDetailOpen(false);
    setResourceEditOpen(false);
    setResourceEditForm(emptyResourceEditForm);
    setResourceEditError(null);
    setStatusMenuResourceId(null);
  };

  const moveResourcePage = (delta) => {
    const nextPage = resourcePage + delta;
    if (nextPage < 0) return;
    if (resourceTotalPages > 0 && nextPage >= resourceTotalPages) return;
    setResourcePage(nextPage);
    setResourceDetailOpen(false);
    setResourceEditOpen(false);
    setResourceEditForm(emptyResourceEditForm);
    setResourceEditError(null);
    setSelected(null);
    setStatusMenuResourceId(null);
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
      fetchUploadingDocs();
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
    setContentTarget(null);
    setContentResult(emptyResourceContent);
    setContentError(null);
    setContentTab("matched");
    setResourcePageIndexBaseMap({});
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

  const openDocumentViewer = (item, options = {}) => {
    const effectivePageIndex =
      typeof options.pageIndex === "number" ? options.pageIndex : item.bestPageIndex;
    const effectiveQuery =
      typeof options.query === "string" ? options.query.trim() : (search.q?.trim() || "");
    const effectivePageIndexBase = options.pageIndexBase === 1 ? 1 : 0;

    const hash = new URLSearchParams();
    const pageNumber = toPdfPageNumber(effectivePageIndex, effectivePageIndexBase);
    if (Number.isInteger(pageNumber) && pageNumber >= 1) {
      hash.set("page", String(pageNumber));
    }
    if (effectiveQuery) {
      hash.set("search", effectiveQuery);
    }

    const baseUrl = `/api/resources/${item.resourceId}/file`;
    const target = hash.toString() ? `${baseUrl}#${hash.toString()}` : baseUrl;
    window.open(target, "_blank", "noopener,noreferrer");
  };

  const openLinkSource = (item) => {
    if (!item?.url) return;
    window.open(item.url, "_blank", "noopener,noreferrer");
  };

  const openResourceSource = (item) => {
    if (!item) return;
    if (item.type === "link") {
      openLinkSource(item);
      return;
    }
    if (item.type === "document") {
      openDocumentViewer(item, { query: "" });
    }
  };

  const openMemoViewer = (item) => {
    if (!item?.memo) return;
    setMemoViewer({
      title: item.title || "(제목 없음)",
      memo: item.memo
    });
  };

  const closeMemoViewer = () => {
    setMemoViewer(null);
  };

  const onResourceItemClick = (event, item) => {
    if (event?.target instanceof Element) {
      const blocked = event.target.closest(
        "button, a, input, textarea, select, .status-dot-menu, .resource-item-memo"
      );
      if (blocked) return;
    }
    loadResourceContent(item);
  };

  const loadResourceContent = async (item) => {
    setContentTarget(item);
    setContentLoading(true);
    setContentError(null);
    setContentResult(emptyResourceContent);
    const activeQuery = (workspace === "resources" ? resourceSearchQuery : search.q)?.trim() || "";
    setContentTab(activeQuery ? "matched" : "all");

    const params = new URLSearchParams();
    if (activeQuery) {
      params.set("q", activeQuery);
    }

    const queryString = params.toString();
    const url = queryString
      ? `/api/resources/${item.resourceId}/content?${queryString}`
      : `/api/resources/${item.resourceId}/content`;

    try {
      const res = await fetch(url);
      const data = await res.json();
      if (!res.ok) throw new Error(data?.message || "본문 조회 실패");
      setContentResult(data);
      const pageIndexBase = detectPageIndexBase(data?.chunks || []);
      setResourcePageIndexBaseMap((prev) => ({
        ...prev,
        [item.resourceId]: pageIndexBase
      }));
    } catch (err) {
      setContentError(err.message);
      setContentResult(emptyResourceContent);
    } finally {
      setContentLoading(false);
    }
  };

  const resolvePageIndexBase = async (resourceId) => {
    const cached = resourcePageIndexBaseMap[resourceId];
    if (cached === 0 || cached === 1) {
      return cached;
    }

    try {
      const res = await fetch(`/api/resources/${resourceId}/content`);
      const data = await res.json();
      if (!res.ok) throw new Error(data?.message || "본문 조회 실패");

      const pageIndexBase = detectPageIndexBase(data?.chunks || []);
      setResourcePageIndexBaseMap((prev) => ({
        ...prev,
        [resourceId]: pageIndexBase
      }));
      return pageIndexBase;
    } catch (error) {
      return 0;
    }
  };

  const openDocumentViewerFromSearch = async (item) => {
    const pageIndexBase = await resolvePageIndexBase(item.resourceId);
    openDocumentViewer(item, { pageIndexBase });
  };

  const closeContentViewer = () => {
    setContentTarget(null);
    setContentResult(emptyResourceContent);
    setContentError(null);
    setContentLoading(false);
    setContentTab("matched");
  };

  useEffect(() => {
    if (workspace === "search" || workspace === "resources") return;
    setContentTarget(null);
    setContentResult(emptyResourceContent);
    setContentError(null);
    setContentLoading(false);
    setContentTab("matched");
    setResourcePageIndexBaseMap({});
  }, [workspace]);

  useEffect(() => {
    if (!contentTarget) return;

    const originalOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    const onKeyDown = (event) => {
      if (event.key !== "Escape") return;
      setContentTarget(null);
      setContentResult(emptyResourceContent);
      setContentError(null);
      setContentLoading(false);
      setContentTab("matched");
    };

    window.addEventListener("keydown", onKeyDown);
    return () => {
      document.body.style.overflow = originalOverflow;
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [contentTarget]);

  const contentPageIndexBase = contentTarget
    ? (resourcePageIndexBaseMap[contentTarget.resourceId] ?? detectPageIndexBase(contentResult.chunks || []))
    : 0;
  const matchedChunks = (contentResult.chunks || []).filter((chunk) => chunk.matched);
  const visibleChunks = contentTab === "matched" ? matchedChunks : (contentResult.chunks || []);

  return (
    <div className="page playful">
      <header className="hero">
        <div className="hero-copy">
          <span className="pill">Collecta Workbench</span>
          <h1>
            {workspace === "upload"
              ? "자료 업로드"
              : workspace === "resources"
                ? "전체 자료"
                : "검색 워크벤치"}
          </h1>
          <p>
            {workspace === "upload"
              ? "문서와 링크를 등록하세요."
              : workspace === "resources"
                ? "필터에서 상태와 타입을 조합해 원하는 자료만 빠르게 확인하세요."
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
            className={workspace === "resources" ? "active" : ""}
            onClick={() => setWorkspace("resources")}
          >
            자료
          </button>
          <button
            className={workspace === "search" ? "active" : ""}
            onClick={() => setWorkspace("search")}
          >
            검색
          </button>
        </div>
      </header>

      {workspace === "upload" && (
        <section className="stack">
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

            <section className="card list playful-card">
              <div className="list-header">
                <h2>업로드 중 문서</h2>
                <div className="actions">
                  <span className="hint">자동 갱신 5초</span>
                  <button type="button" onClick={fetchUploadingDocs} disabled={uploadingLoading}>
                    {uploadingLoading ? "갱신 중..." : "새로고침"}
                  </button>
                </div>
              </div>
              {uploadingError && <p className="error-msg">{uploadingError}</p>}
              {!uploadingError && uploadingDocs.length === 0 ? (
                <p className="muted">업로드 중인 문서가 없습니다.</p>
              ) : (
                <ul className="list-items">
                  {uploadingDocs.map((item) => (
                    <li key={item.resourceId} className={`status ${item.status}`}>
                      <div>
                        <strong>{item.title || "(제목 없음)"}</strong>
                        <span className="meta">
                          #{item.resourceId} · {statusLabel(item.status)}
                        </span>
                      </div>
                      <div className="right">
                        <span className="badge">{statusLabel(item.status)}</span>
                        {item.stage && <span className="stage">{stageLabel(item.stage)}</span>}
                        <button
                          type="button"
                          className="ghost compact"
                          onClick={(event) => {
                            event.stopPropagation();
                            cancelIngest(item.resourceId);
                          }}
                          disabled={detailLoading}
                        >
                          중단
                        </button>
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
            </section>
          </div>
        </section>
      )}

      {workspace === "resources" && (
        <section className="stack resources-page">
          <section className="card list playful-card">
            <div className="list-header resources-list-header">
              <h2>자료 목록</h2>
              <div className="list-header-right">
                <div className="actions">
                  <span className="hint">{resourceSearchActive ? "검색 결과 고정" : "자동 갱신 5초"}</span>
                  <div className="resource-filter-wrap" ref={resourceFilterRef}>
                    <button
                      type="button"
                      className={`resource-filter-trigger${resourceFilterOpen ? " active" : ""}`}
                      onClick={() => setResourceFilterOpen((prev) => !prev)}
                      aria-expanded={resourceFilterOpen}
                      aria-haspopup="true"
                    >
                      필터
                      <span className="resource-filter-current">
                        {resourceStatusFilterLabel(resourceStatusFilter)} ·{" "}
                        {resourceTypeFilterLabel(resourceTypeFilter)} ·{" "}
                        {resourcePinnedFilterLabel(resourcePinnedFilter)}
                      </span>
                    </button>
                    {resourceFilterOpen && (
                      <div className="resource-filter-popover">
                        <div className="resource-filter-section">
                          <span className="resource-filter-title">상태</span>
                          <div className="resource-filter-options">
                            <button
                              type="button"
                              className={resourceStatusFilter === "active" ? "active" : ""}
                              onClick={() => switchResourceStatusFilter("active")}
                            >
                              할 일/진행중
                            </button>
                            <button
                              type="button"
                              className={resourceStatusFilter === "todo" ? "active" : ""}
                              onClick={() => switchResourceStatusFilter("todo")}
                            >
                              할 일
                            </button>
                            <button
                              type="button"
                              className={resourceStatusFilter === "in_progress" ? "active" : ""}
                              onClick={() => switchResourceStatusFilter("in_progress")}
                            >
                              진행중
                            </button>
                            <button
                              type="button"
                              className={resourceStatusFilter === "done" ? "active" : ""}
                              onClick={() => switchResourceStatusFilter("done")}
                            >
                              완료
                            </button>
                            <button
                              type="button"
                              className={resourceStatusFilter === "all" ? "active" : ""}
                              onClick={() => switchResourceStatusFilter("all")}
                            >
                              전체 자료
                            </button>
                          </div>
                        </div>
                        <div className="resource-filter-section">
                          <span className="resource-filter-title">타입</span>
                          <div className="resource-filter-options">
                            <button
                              type="button"
                              className={resourceTypeFilter === "" ? "active" : ""}
                              onClick={() => switchResourceTypeFilter("")}
                            >
                              전체 타입
                            </button>
                            <button
                              type="button"
                              className={resourceTypeFilter === "document" ? "active" : ""}
                              onClick={() => switchResourceTypeFilter("document")}
                            >
                              문서
                            </button>
                            <button
                              type="button"
                              className={resourceTypeFilter === "link" ? "active" : ""}
                              onClick={() => switchResourceTypeFilter("link")}
                            >
                              링크
                            </button>
                          </div>
                        </div>
                        <div className="resource-filter-section">
                          <span className="resource-filter-title">중요 표시</span>
                          <div className="resource-filter-options">
                            <button
                              type="button"
                              className={resourcePinnedFilter === "all" ? "active" : ""}
                              onClick={() => switchResourcePinnedFilter("all")}
                            >
                              전체
                            </button>
                            <button
                              type="button"
                              className={resourcePinnedFilter === "pinned" ? "active" : ""}
                              onClick={() => switchResourcePinnedFilter("pinned")}
                            >
                              중요만
                            </button>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() =>
                      fetchRecent(
                        resourcePage,
                        resourceStatusFilter,
                        resourceTypeFilter,
                        resourcePinnedFilter,
                        resourceSearchQuery
                      )}
                    disabled={recentLoading}
                  >
                    {recentLoading ? "갱신 중..." : "새로고침"}
                  </button>
                </div>

              </div>
            </div>

            <div className="list-summary list-summary-with-search">
              <div className="list-summary-meta">
                <span>총 {resourceTotal}건</span>
                <span>페이지 {resourceTotal === 0 ? 0 : resourcePage + 1}/{resourceTotalPages || 0}</span>
                <span>
                  필터 {resourceStatusFilterLabel(resourceStatusFilter)} ·{" "}
                  {resourceTypeFilterLabel(resourceTypeFilter)} ·{" "}
                  {resourcePinnedFilterLabel(resourcePinnedFilter)}
                </span>
              </div>
              <form className="resource-quick-search" onSubmit={submitResourceSearch}>
                <div className="resource-search-input-wrap">
                  <input
                    value={resourceSearchInput}
                    onChange={(event) => setResourceSearchInput(event.target.value)}
                    placeholder="자료 검색어 입력 (하이브리드)"
                  />
                  {(resourceSearchInput || resourceSearchActive) && (
                    <button
                      type="button"
                      className="resource-search-clear"
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={clearResourceSearch}
                      aria-label="검색어 지우기"
                      title="검색어 지우기"
                    >
                      ×
                    </button>
                  )}
                </div>
                <button type="submit" className="ghost compact" disabled={recentLoading}>
                  검색
                </button>
              </form>
            </div>

            {resourceError && <p className="error-msg">{resourceError}</p>}

            {!resourceError && recent.length === 0 ? (
              <p className="muted">
                {resourceSearchActive
                  ? `"${resourceSearchQuery}" 검색 결과가 없습니다.`
                  : resourceStatusFilter === "all"
                    ? "자료가 없습니다."
                    : `${resourceStatusFilterLabel(resourceStatusFilter)} 자료가 없습니다.`}
              </p>
            ) : (
              <ul className="list-items">
                {recent.map((item) => (
                  <li
                    key={item.resourceId}
                    className={`status resource-item ${item.status}${item.isPinned ? " pinned" : ""}`}
                    onClick={(event) => onResourceItemClick(event, item)}
                  >
                    <div className="resource-item-body">
                      <div className="resource-item-head">
                        <div className="resource-item-title-group">
                          <div className="status-dot-menu-wrap">
                            <button
                              type="button"
                              className={`status-dot status-dot-button ${item.status}`}
                              title={`상태 변경 (${statusLabel(item.status)})`}
                              aria-label={`상태 변경 (${statusLabel(item.status)})`}
                              onClick={() =>
                                setStatusMenuResourceId((prev) =>
                                  prev === item.resourceId ? null : item.resourceId
                                )}
                              disabled={statusUpdatingId === item.resourceId}
                            />
                            {statusMenuResourceId === item.resourceId && (
                              <div className="status-dot-menu">
                                <button
                                  type="button"
                                  className={item.status === "todo" ? "active" : ""}
                                  onClick={() => updateResourceStatus(item.resourceId, "todo")}
                                  disabled={statusUpdatingId === item.resourceId}
                                >
                                  할 일
                                </button>
                                <button
                                  type="button"
                                  className={item.status === "in_progress" ? "active" : ""}
                                  onClick={() => updateResourceStatus(item.resourceId, "in_progress")}
                                  disabled={statusUpdatingId === item.resourceId}
                                >
                                  진행중
                                </button>
                                <button
                                  type="button"
                                  className={item.status === "done" ? "active" : ""}
                                  onClick={() => updateResourceStatus(item.resourceId, "done")}
                                  disabled={statusUpdatingId === item.resourceId}
                                >
                                  완료
                                </button>
                              </div>
                            )}
                          </div>
                          <button
                            type="button"
                            className={`pin-toggle${item.isPinned ? " active" : ""}`}
                            title={item.isPinned ? "중요 해제" : "중요 표시"}
                            aria-label={item.isPinned ? "중요 해제" : "중요 표시"}
                            onClick={() => updateResourcePinned(item.resourceId, !item.isPinned)}
                            disabled={statusUpdatingId === item.resourceId}
                          >
                            {item.isPinned ? "★" : "☆"}
                          </button>
                          <button
                            type="button"
                            className="resource-title-link"
                            onClick={() => openResourceSource(item)}
                            disabled={item.type === "link" && !item.url}
                            title={item.type === "document" ? "원본 자료 열기" : "원본 링크 열기"}
                          >
                            {item.title || "(제목 없음)"}
                          </button>
                          {item.tags?.length > 0 && (
                            <span className="resource-tags-inline">
                              tags: {item.tags.join(", ")}
                            </span>
                          )}
                        </div>
                        <span className="resource-item-meta-inline">
                          #{item.resourceId} · {item.type}
                        </span>
                      </div>
                      <div className="resource-item-row">
                        {item.memo ? (
                          <p
                            className="resource-item-memo"
                            title="클릭해 전체 메모 보기"
                            onClick={() => openMemoViewer(item)}
                          >
                            {item.memo}
                          </p>
                        ) : (
                          <span className="resource-item-memo-empty" />
                        )}
                        {resourceSearchActive && (
                          <span className="resource-item-search-meta">
                            score {formatScore(item.bestScore)} · match {item.matchCount ?? 0}
                          </span>
                        )}
                        <div className="resource-item-actions">
                          <div className="resource-item-action-buttons">
                            <button
                              type="button"
                              className="ghost compact"
                              onClick={() => openResourceEdit(item)}
                            >
                              수정
                            </button>
                            <button
                              type="button"
                              className="ghost compact"
                              onClick={() => openResourceDetail(item.resourceId)}
                            >
                              상세 보기
                            </button>
                          </div>
                        </div>
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )}

            <div className="pager">
              <button
                type="button"
                className="ghost"
                onClick={() => moveResourcePage(-1)}
                disabled={recentLoading || resourcePage <= 0}
              >
                이전
              </button>
              <button
                type="button"
                className="ghost"
                onClick={() => moveResourcePage(1)}
                disabled={
                  recentLoading ||
                  resourceTotalPages === 0 ||
                  resourcePage >= resourceTotalPages - 1
                }
              >
                다음
              </button>
            </div>
          </section>
        </section>
      )}

      {workspace === "resources" && resourceDetailOpen && (
        <div
          className="resource-detail-backdrop"
          onClick={closeResourceDetail}
          role="presentation"
        >
          <section
            className="resource-detail-modal"
            role="dialog"
            aria-modal="true"
            aria-label="자료 상세"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="resource-detail-head">
              <h3>상세</h3>
              <button type="button" className="ghost" onClick={closeResourceDetail}>
                닫기
              </button>
            </div>
            <div className="resource-detail-body">
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
        </div>
      )}

      {workspace === "resources" && resourceEditOpen && (
        <div
          className="resource-edit-backdrop"
          onClick={closeResourceEdit}
          role="presentation"
        >
          <section
            className="resource-edit-modal"
            role="dialog"
            aria-modal="true"
            aria-label="자료 수정"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="resource-edit-head">
              <h3>자료 수정</h3>
              <button
                type="button"
                className="ghost"
                onClick={closeResourceEdit}
                disabled={resourceEditSaving}
              >
                닫기
              </button>
            </div>
            <form className="resource-edit-body" onSubmit={saveResourceEdit}>
              {resourceEditError && <p className="error-msg">{resourceEditError}</p>}
              <div className="row">
                <label>제목</label>
                <input
                  value={resourceEditForm.title}
                  onChange={(event) =>
                    setResourceEditForm((prev) => ({ ...prev, title: event.target.value }))
                  }
                  disabled={resourceEditSaving}
                />
              </div>
              <div className="row">
                <label>메모</label>
                <textarea
                  value={resourceEditForm.memo}
                  onChange={(event) =>
                    setResourceEditForm((prev) => ({ ...prev, memo: event.target.value }))
                  }
                  disabled={resourceEditSaving}
                />
              </div>
              <div className="row">
                <label>태그 (쉼표 구분)</label>
                <input
                  value={resourceEditForm.tags}
                  onChange={(event) =>
                    setResourceEditForm((prev) => ({ ...prev, tags: event.target.value }))
                  }
                  disabled={resourceEditSaving}
                />
              </div>
              <div className="row inline">
                <label>상태</label>
                <select
                  value={resourceEditForm.status}
                  onChange={(event) =>
                    setResourceEditForm((prev) => ({ ...prev, status: event.target.value }))
                  }
                  disabled={resourceEditSaving}
                >
                  <option value="todo">할 일</option>
                  <option value="in_progress">진행 중</option>
                  <option value="done">완료</option>
                </select>
                <label className="checkbox">
                  <input
                    type="checkbox"
                    checked={resourceEditForm.isPinned}
                    onChange={(event) =>
                      setResourceEditForm((prev) => ({ ...prev, isPinned: event.target.checked }))
                    }
                    disabled={resourceEditSaving}
                  />
                  중요 표시
                </label>
              </div>
              {resourceEditForm.type === "link" && (
                <div className="row">
                  <label>URL</label>
                  <input
                    value={resourceEditForm.url}
                    onChange={(event) =>
                      setResourceEditForm((prev) => ({ ...prev, url: event.target.value }))
                    }
                    disabled={resourceEditSaving}
                  />
                </div>
              )}
              <div className="resource-edit-actions">
                <button
                  type="button"
                  className="ghost"
                  onClick={closeResourceEdit}
                  disabled={resourceEditSaving}
                >
                  취소
                </button>
                <button type="submit" disabled={resourceEditSaving}>
                  {resourceEditSaving ? "저장 중..." : "저장"}
                </button>
              </div>
            </form>
          </section>
        </div>
      )}

      {workspace === "resources" && memoViewer && (
        <div
          className="memo-viewer-backdrop"
          onClick={closeMemoViewer}
          role="presentation"
        >
          <section
            className="memo-viewer-modal"
            role="dialog"
            aria-modal="true"
            aria-label="전체 메모"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="memo-viewer-head">
              <h3>전체 메모</h3>
              <button type="button" className="ghost" onClick={closeMemoViewer}>
                닫기
              </button>
            </div>
            <div className="memo-viewer-body">
              <p className="meta">{memoViewer.title}</p>
              <p className="memo-viewer-text">{memoViewer.memo}</p>
            </div>
          </section>
        </div>
      )}

      {workspace === "search" && (
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
                {searchResult.items.map((item) => {
                  const knownPageIndexBase = resourcePageIndexBaseMap[item.resourceId] ?? 0;
                  const bestPageNumber = toDisplayPageNumber(item.bestPageIndex, knownPageIndexBase);

                  return (
                    <li key={item.resourceId} className="search-item">
                      <div className="search-head">
                        <strong>{item.title || "(제목 없음)"}</strong>
                        <span className="badge small">{item.type}</span>
                      </div>
                      <p className="meta">
                        #{item.resourceId} · score {formatScore(item.bestScore)} · match {item.matchCount}
                      </p>
                      {bestPageNumber !== null && (
                        <p className="meta">best page: {bestPageNumber}</p>
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
                        <button
                          type="button"
                          className="ghost"
                          onClick={() => loadResourceContent(item)}
                        >
                          본문 보기
                        </button>
                        {item.type === "document" && (
                          <button
                            type="button"
                            className="ghost"
                            onClick={() => openDocumentViewerFromSearch(item)}
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
                  );
                })}
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

      {(workspace === "search" || workspace === "resources") && contentTarget && (
        <div
          className="content-drawer-backdrop"
          onClick={closeContentViewer}
          role="presentation"
        >
          <aside
            className="content-drawer"
            role="dialog"
            aria-modal="true"
            aria-label="본문 뷰어"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="content-drawer-head">
              <div>
                <h3>본문 뷰어</h3>
                <p className="meta">
                  {contentResult.title || contentTarget.title || "(제목 없음)"}
                </p>
              </div>
              <button
                type="button"
                className="ghost"
                onClick={closeContentViewer}
              >
                닫기
              </button>
            </div>

            <div className="content-drawer-body">
              {contentLoading && <p className="muted">본문 불러오는 중...</p>}
              {contentError && <p className="error-msg">{contentError}</p>}

              {!contentLoading && !contentError && (
                <div className="content-view">
                  <div className="content-head">
                    <div>
                      <p className="meta">
                        #{contentTarget.resourceId} · {contentTarget.type} · chunk {contentResult.chunkCount}
                      </p>
                    </div>
                    <div className="search-actions">
                      {contentTarget.type === "document" && (
                        <button
                          type="button"
                          className="ghost"
                          onClick={() =>
                            openDocumentViewer(contentTarget, {
                              pageIndexBase: contentPageIndexBase
                            })
                          }
                        >
                          PDF 열기
                        </button>
                      )}
                      {contentTarget.type === "link" && contentTarget.url && (
                        <button
                          type="button"
                          className="ghost"
                          onClick={() => openLinkSource(contentTarget)}
                        >
                          원문 열기
                        </button>
                      )}
                    </div>
                  </div>

                  <div className="content-tabs">
                    <button
                      type="button"
                      className={contentTab === "matched" ? "active" : ""}
                      onClick={() => setContentTab("matched")}
                    >
                      일치 청크 ({matchedChunks.length})
                    </button>
                    <button
                      type="button"
                      className={contentTab === "all" ? "active" : ""}
                      onClick={() => setContentTab("all")}
                    >
                      전체 본문 ({contentResult.chunkCount})
                    </button>
                  </div>

                  {visibleChunks.length === 0 ? (
                    <p className="muted">
                      {contentTab === "matched"
                        ? "검색어와 일치하는 청크가 없습니다."
                        : "표시할 본문 청크가 없습니다."}
                    </p>
                  ) : (
                    <ul className="chunk-list">
                      {visibleChunks.map((chunk, index) => (
                        <li
                          key={`${chunk.position ?? "p"}-${chunk.pageIndex ?? "n"}-${index}`}
                          className="chunk-item"
                        >
                          <div className="chunk-meta-row">
                            <span className="meta">
                              page {toDisplayPageNumber(chunk.pageIndex, contentPageIndexBase) ?? "-"} ·
                              pos {typeof chunk.position === "number" ? chunk.position : "-"}
                            </span>
                            <div className="chunk-meta-actions">
                              {chunk.matched && <span className="badge small">match</span>}
                              {contentTarget.type === "document" && typeof chunk.pageIndex === "number" && (
                                <button
                                  type="button"
                                  className="ghost chunk-open"
                                  onClick={() =>
                                    openDocumentViewer(contentTarget, {
                                      pageIndex: chunk.pageIndex,
                                      query: "",
                                      pageIndexBase: contentPageIndexBase
                                    })
                                  }
                                >
                                  이 페이지 열기
                                </button>
                              )}
                            </div>
                          </div>
                          {chunk.highlightedText ? (
                            <p
                              className="snippet"
                              dangerouslySetInnerHTML={{ __html: chunk.highlightedText }}
                            />
                          ) : (
                            <p className="chunk-text">{chunk.text}</p>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </div>
          </aside>
        </div>
      )}
    </div>
  );
}
