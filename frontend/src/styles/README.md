样式入口为 index.css。tokens.css 集中维护色彩与字体，功能样式按用户、管理、评测、协调和助手拆分；workspace.css 维护导航布局，theme.css 统一组件外观。responsive.css 与 workspace-responsive.css 保留原有覆盖顺序。新增表单、弹窗和图表样式分别放入对应文件，不再追加到旧的大文件。

meal-planning.css 维护饮食分析页的今日总览、餐次状态与下一餐搭配卡片；窄屏使用单列推荐卡和两列餐次，继续复用共享 Button 与表单样式。

buttons.css 在功能样式之后统一主操作、次操作以及弹窗按钮，使用项目绿色、9px 圆角和窄屏 44px 点击高度。操作统一使用 components/Button：variant 可选 primary、secondary、danger、ghost、plain；导航、图标和对话快捷按钮使用 plain 保留专属布局。busy 自动禁用按钮并设置 aria-busy，可配合 loadingText 显示进行中提示。默认 type=button，表单提交必须显式 type=submit，避免无意提交。既有布局所需 className 可以继续传入。
