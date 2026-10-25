# Antigravity Workspace Guidelines

## Git 与远程仓库规范 (Git & Remote Operations)

> [!IMPORTANT]
> **严禁未经授权推送远端**：
> 1. 在未获得用户在当前对话中明确指令或确认前，**绝对禁止**执行任何推送到远端仓库的命令（包括但不限于 `git push`、推送分支、推送标签 `git push --tags` 或强制推送 `git push -f`）。
> 2. 完成本地代码修改后，仅可执行本地 `git add` 与 `git commit`。提交完成后，需向用户呈现 Commit 哈希与变更要点，并主动征询用户是否需要推送到远端。
> 3. 只有在收到用户明确指示（如“推送到 GitHub”、“push 上去”等）后，方可执行推送操作。
