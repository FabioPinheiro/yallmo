# Sourced at the end of the agent's .bashrc; never enable history for scripts.
[[ $- == *i* ]] || return 0

# Share Zsh's private volume, not its history file. Never import old host history.
mkdir -p -- "$HOME/.zsh-data"
HISTFILE="$HOME/.zsh-data/bash_history"
HISTSIZE=100000
HISTFILESIZE=100000
shopt -s histappend

_yallmo_bash_history_sync() {
  # Flush first, then reload: history -n can skip another session's entries when
  # our pending command changes the file's line offsets. Keep memory on failure.
  history -a || return
  history -c
  history -r
}

_yallmo_bash_history_install_hook() {
  # Bash supports both a command string and an array. Preserve every existing
  # entry and run our hook last so earlier hooks still receive the exit status.
  local command
  for command in "${PROMPT_COMMAND[@]}"; do
    [[ "$command" == _yallmo_bash_history_sync ]] && return 0
  done
  PROMPT_COMMAND=("${PROMPT_COMMAND[@]}" _yallmo_bash_history_sync)
}
_yallmo_bash_history_install_hook
unset -f _yallmo_bash_history_install_hook
