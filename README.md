# Spring Boot JNI - React Frontend

A modern full-stack application demonstrating Java Native Interface (JNI) integration with Spring Boot backend and React frontend.

## Tech Stack

### Backend
- Spring Boot 2.6.13
- Java 8
- JNI (Java Native Interface)
- Maven

### Frontend
- React 18
- Vite 6
- Tailwind CSS
- Ant Design
- lucide-react
- React Router
- Axios

## Prerequisites

- Java 8 or higher
- Maven 3.x
- Node.js 22.13.1 or below (for development)

## Development

### Backend Only
```bash
mvn spring-boot:run
```
Backend will run on http://localhost:8080

### Frontend Only
```bash
cd frontend
npm install
npm run dev
```
Frontend will run on http://localhost:5173 and proxy API calls to backend

### Run Both (Development Mode)
1. Terminal 1 - Start backend:
   ```bash
   mvn spring-boot:run
   ```

2. Terminal 2 - Start frontend:
   ```bash
   cd frontend
   npm run dev
   ```

3. Open http://localhost:5173 in your browser

## Production Build

### Option 1: Using Maven (Automated)
```bash
mvn clean package
java -jar target/springboot-jni-0.0.1-SNAPSHOT.jar
```
Maven will automatically build the frontend and include it in the JAR.

### Option 2: Manual Build
```bash
# Windows
build-frontend.bat
mvn package

# Unix/Mac
chmod +x build-frontend.sh
./build-frontend.sh
mvn package
```

Then run:
```bash
java -jar target/springboot-jni-0.0.1-SNAPSHOT.jar
```

Access the application at http://localhost:8080

## Project Structure

```
springboot-jni/
├── src/
│   └── main/
│       ├── java/
│       │   └── springbootjni/
│       │       ├── common/         # JNI bridge
│       │       ├── config/         # Spring configuration (CORS, etc.)
│       │       ├── controller/     # REST API endpoints
│       │       ├── dto/            # Data transfer objects
│       │       └── service/        # Business logic
│       └── resources/
│           ├── static/             # Frontend production build (auto-generated)
│           ├── application.properties
│           └── spectra.dll         # Native library
├── frontend/
│   ├── src/
│   │   ├── api/                   # API client
│   │   ├── components/            # React components
│   │   ├── pages/                 # Page components
│   │   ├── App.jsx
│   │   └── main.jsx
│   ├── package.json
│   ├── vite.config.js
│   └── tailwind.config.js
├── pom.xml
├── build-frontend.bat             # Windows build script
└── build-frontend.sh              # Unix/Mac build script
```

## Features

- **JNI Test**: Test Java Native Interface functionality by retrieving spectrum data from C++ DLL
- **JNI Bridge**: Start a background listener for continuous FPGA data reception
- Modern, responsive UI with gradient backgrounds and smooth animations
- Real-time API communication with loading states and error handling
- Full-stack integration with CORS support for development

## API Endpoints

- `GET /api/jni/test` - Test JNI functionality and retrieve spectrum data
- `GET /api/jni/jni_bridge` - Start JNI bridge listener in background thread

## Notes

- The native DLL (`spectra.dll`) is loaded from resources at runtime
- Frontend dev server (Vite) proxies API calls to backend on port 8080
- Production build serves frontend static files from Spring Boot
- CORS is configured for development mode (http://localhost:5173)
