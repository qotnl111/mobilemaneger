@echo off
rem 게임 관리 PC 프로그램 빌드 (윈도우 기본 csc.exe 사용)
set CSC=%WINDIR%\Microsoft.NET\Framework644.0.30319\csc.exe
"%CSC%" /nologo /target:winexe /out:GameManagerPC.exe /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Web.Extensions.dll GameManagerPC.cs
