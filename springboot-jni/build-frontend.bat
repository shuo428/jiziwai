@echo off
echo Building React frontend...
cd frontend

echo Installing dependencies...
call npm install

echo Building production bundle...
call npm run build

echo Copying build to Spring Boot static resources...
if exist "..\src\main\resources\static" rd /s /q "..\src\main\resources\static"
mkdir "..\src\main\resources\static"
xcopy /s /y dist\* "..\src\main\resources\static\"

cd ..

echo Frontend build completed successfully!
echo You can now run: mvn package
