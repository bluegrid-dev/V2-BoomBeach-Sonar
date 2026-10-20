Option Explicit
Dim files, shell, root, powershell, command
Set files = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
root = files.GetParentFolderName(WScript.ScriptFullName)
powershell = shell.ExpandEnvironmentStrings("%SystemRoot%") & "\System32\WindowsPowerShell\v1.0\powershell.exe"
shell.CurrentDirectory = root
command = Chr(34) & powershell & Chr(34) & " -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File " & Chr(34) & root & "\Control.ps1" & Chr(34)
shell.Run command, 0, False
