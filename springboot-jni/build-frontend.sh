#!/bin/bash

echo "Building React frontend..."
cd frontend

echo "Installing dependencies..."
npm install

echo "Building production bundle..."
npm run build

echo "Copying build to Spring Boot static resources..."
rm -rf ../src/main/resources/static/*
cp -r dist/* ../src/main/resources/static/

cd ..

echo "Frontend build completed successfully!"
echo "You can now run: mvn package"
