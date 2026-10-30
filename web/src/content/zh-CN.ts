/**
 * 全部用户可见中文文案的唯一来源（plan D14）。
 * 组件内不得内联中文字符串；新增文案一律加在这里。
 */

export const zhCN = {
  brand: {
    name: '知识问答',
    tagline: '企业内部知识库',
  },

  theme: {
    /** 顶栏主题切换的三项文案（ThemeSwitch 受控，默认文案为英文） */
    labels: {
      auto: '跟随系统',
      light: '浅色',
      dark: '深色',
    },
    /** 主题切换控件的无障碍名称（ThemeSwitch 不转发 aria-label，用分组容器承载） */
    switchLabel: '外观主题',
  },

  time: {
    justNow: '刚刚',
    minutesAgo: '{count} 分钟前',
    hoursAgo: '{count} 小时前',
    daysAgo: '{count} 天前',
    unknown: '—',
  },

  nav: {
    home: '首页',
    chat: '问答',
    documents: '文档库',
    jobs: '任务中心',
    /** 导航地标的无障碍名称 */
    landmark: '主导航',
  },

  /** ConsoleShell 内建文案的本地化（默认值为英文） */
  shell: {
    skipToContent: '跳到主内容',
    collapseNav: '收起侧栏',
    expandNav: '展开侧栏',
    openNav: '打开导航',
  },

  greeting: {
    /** 无文档或无法判断时段时的中性问候 */
    neutral: '你好',
    morning: '早上好',
    afternoon: '下午好',
    evening: '晚上好',
  },

  /** 问候区副标题模板，`{count}` 由调用方替换 */
  subtitle: {
    loading: '正在读取知识库…',
    empty: '知识库还是空的，上传第一份文档就能开始提问',
    withCount: '知识库里已有 {count} 份文档，直接提问即可',
    processing: '文档正在处理中，处理完成后即可用于提问',
    /** 数据读取失败：只说服务不可用，不得说成"知识库为空" */
    error: '暂时读不到知识库内容，服务恢复后即可提问',
  },

  ask: {
    placeholder: '输入你的问题，按 Enter 发送',
    hint: 'Enter 发送，Shift + Enter 换行',
    focusedShortcutHint: '按 / 或 Ctrl + K 可随时聚焦这里',
    send: '发送',
    /** ChatSendButton 内建提示中的"换行"（Shift + Enter 一侧） */
    warp: '换行',
    disabledEmpty: '知识库还没有可用的文档，先上传一份再提问',
    disabledError: '服务暂时不可用，恢复后即可提问',
  },

  suggestions: {
    title: '试试这样问',
    /**
     * 预置问题池（plan D13）：前两条为常规预置；
     * 可检索文档不足 2 条时，从池中继续取用补齐到 4 条建议问题（data-model §1.4）。
     */
    presets: [
      '这个知识库都收录了哪些内容？',
      '我该怎么找到想要的答案？',
      '这些文档是谁维护的？',
      '文档更新后，答案会自动更新吗？',
    ],
    fromDocument: '《{title}》讲了什么？',
    disabled: '先上传一份文档，建议问题才可用',
  },

  overview: {
    recentTitle: '最近更新',
    recentCount: '共 {count} 份文档',
    browseAll: '浏览全部文档',
    processingNotice: '知识库正在处理中，部分问题可能没有结果',
    loadingLabel: '正在读取最近更新的文档',
  },

  onboarding: {
    title: '知识库还是空的',
    description: '三步就能开始问答，上传的文档只在你自己的知识库内使用',
    steps: [
      { title: '上传文档', description: '支持 txt、md、markdown，单份不超过 10 MB' },
      { title: '自动处理', description: '系统会自动切分并建立索引，通常很快完成' },
      { title: '开始提问', description: '处理完成后回到首页，直接输入问题即可' },
    ],
    cta: '上传第一份文档',
  },

  actionCard: {
    title: '上传新文档，让知识库更聪明',
    description: '支持 txt、md、markdown，单份不超过 10 MB',
    cta: '上传文档',
  },

  recentChats: {
    title: '最近对话',
    restore: '继续这个会话',
  },

  document: {
    title: '文档详情',
    loading: '正在读取文档详情…',
    fields: {
      docName: '文档标识',
      title: '标题',
      status: '状态',
      version: '版本',
      chunkCount: '切片数',
      fileSize: '文件大小',
      mimeType: '文件类型',
      createdAt: '创建时间',
      updatedAt: '更新时间',
      source: '来源标记',
    },
    unknown: '—',
    sourceNote: '来源标记由服务端固定返回，不代表真实上传渠道',
    notFound: '该文档不存在或已被移除',
    download: '下载原文',
  },

  /** 列表/详情文档状态文案（颜色映射见 components/DocumentStatusTag.tsx） */
  documentStatus: {
    INGESTED: '可检索',
    INGESTING: '处理中',
    PARTIAL: '部分成功',
    FAILED: '处理失败',
    unknown: '未知状态',
  },

  upload: {
    title: '上传文档',
    description: '支持 txt、md、markdown，单份不超过 10 MB',
    pickFile: '点击或拖拽文件到此处',
    changeFile: '重新选择',
    docNameLabel: '文档标识',
    docNamePlaceholder: '用于在知识库中唯一标识这份文档',
    titleLabel: '标题（可选）',
    titlePlaceholder: '展示用的中文标题',
    submit: '开始上传',
    cancel: '取消',
    uploading: '正在上传 {percent}%',
    invalidType: '仅支持 txt、md、markdown 格式的文本文件',
    fileTooLarge: '文件过大：直接上传上限 10 MB',
    docNameRequired: '请填写文档标识',
    docNameTooLong: '文档标识不能超过 255 个字符',
    fileRequired: '请先选择文件',
    /** 上传结果文案（data-model §2.2） */
    results: {
      INGESTED: '已入库，可以提问了',
      NOOP: '内容未变化，已跳过重复处理',
      PARTIAL: '部分内容入库成功',
      FAILED: '入库失败',
      accepted: '文档已接收，正在后台处理，稍后可在文档库查看',
    },
    actions: {
      viewDocuments: '查看文档',
      goAsk: '去提问',
      retry: '重新上传',
      close: '完成',
    },
  },

  chat: {
    missingQuestion: '没有收到问题内容',
    missingQuestionHint: '回到首页输入问题即可开始',
    backHome: '返回首页提问',
    yourQuestion: '你的问题',
    answerTitle: '回答',
    loadingAnswer: '正在检索资料并生成回答…',
    citationsTitle: '引用片段',
    /** 引用条目标题模板，`{index}`/`{docName}`/`{chunkIndex}` 由调用方替换 */
    citationSource: '片段 {index} · 文档 {docName} · 第 {chunkIndex} 段',
    noContextTitle: '知识库中未找到相关内容',
    noContextHint: '可以换个说法再问，或先上传相关文档',
    failedTitle: '回答生成失败',
    /** 多轮对话（016-sse-chat-streaming） */
    inputPlaceholder: '继续提问，回车发送（Shift+回车换行）',
    sendQuestion: '发送',
    sendWarp: '换行',
    stopAnswer: '停止',
    newConversation: '新对话',
    stoppedHint: '已停止生成，该轮不会写入会话记忆',
  },

  errors: {
    /** 服务不可用通用文案：不得出现"租户"字样（D4 / SC-003） */
    serviceUnavailable: '服务暂时不可用，请稍后重试',
    /** 知识导航层告警条标题 */
    bannerTitle: '知识库读取失败',
    validationFailed: '提交的内容不符合要求，请检查后重试',
    unsupportedFileType: '仅支持 txt、md、markdown 格式的文本文件',
    fileTooLarge: '文件过大：直接上传上限 10 MB',
    documentNotFound: '该文档不存在或已被移除',
    conflict: '操作冲突，请重试',
    uploadSessionInvalid: '上传会话已失效，请重新上传',
    jobNotFound: '处理任务不存在或已完成',
    unknown: '操作失败，请稍后重试',
  },

  placeholders: {
    documents: {
      title: '文档库即将上线',
      description: '在这里可以浏览全部文档、查看处理进度并管理版本。当前可在首页查看最近更新的文档。',
    },
    jobs: {
      title: '任务中心即将上线',
      description: '在这里可以查看文档处理任务的进度与结果。当前上传后会自动处理，无需手动跟进。',
    },
    backHome: '返回首页',
  },

  common: {
    retry: '重试',
    loading: '加载中…',
    close: '关闭',
    comingSoon: '即将上线',
  },
} as const;

export type ZhCN = typeof zhCN;
