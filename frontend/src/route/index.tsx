import { Navigate, Route, Routes } from "react-router-dom";
import HomePage from "../pages/Home";
import SpectralDataPage from "../pages/SpectralData";
import SpectralDataManagementPage from "../pages/SpectralDataManagement";
import LoginPage from "../pages/Login";
import RegisterPage from "../pages/Register";
import ProfilePage from "../pages/Profile";
import ChatPage from "../pages/Chat";
import CalibrationPage from "../pages/Calibration";
import ConfigManagementPage from "../pages/ConfigManagement";
import HdrCapturePage from "../pages/HdrCapture";
import HdrDarkCapturePage from "../pages/HdrDarkCapture";
import ProtectedRoute from "../components/ProtectedRoute";
import Layout from "../components/Layout";

export default function AppRouter() {
    return (
        <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
            <Route
                path="/"
                element={
                    <ProtectedRoute>
                        <Layout />
                    </ProtectedRoute>
                }
            >
                <Route index element={<Navigate to="/dashboard" replace />} />
                <Route
                    path="/dashboard"
                    element={
                        <ProtectedRoute>
                            <HomePage />
                        </ProtectedRoute>
                    }
                />
                <Route
                    path="/spectral-data"
                    element={
                        <ProtectedRoute>
                            <SpectralDataPage />
                        </ProtectedRoute>
                    }
                />
                <Route
                    path="/hdr-capture"
                    element={
                        <ProtectedRoute>
                            <HdrCapturePage />
                        </ProtectedRoute>
                    }
                />
                <Route
                    path="/hdr-dark-capture"
                    element={
                        <ProtectedRoute>
                            <HdrDarkCapturePage />
                        </ProtectedRoute>
                    }
                />
                <Route
                    path="/config-management"
                    element={
                        <ProtectedRoute>
                            <ConfigManagementPage />
                        </ProtectedRoute>
                    }
                />
                <Route
                    path="/spectral-management"
                    element={
                        <ProtectedRoute>
                            <SpectralDataManagementPage scene="NORMAL" />
                        </ProtectedRoute>
                    }
                />
                <Route
                    path="/hdr-management"
                    element={
                        <ProtectedRoute>
                            <SpectralDataManagementPage scene="HDR" />
                        </ProtectedRoute>
                    }
                />
                <Route
                    path="/calibration"
                    element={
                        <ProtectedRoute>
                            <CalibrationPage mode="NORMAL" />
                        </ProtectedRoute>
                    }
                />
                <Route
                    path="/hdr-calibration"
                    element={
                        <ProtectedRoute>
                            <CalibrationPage mode="HDR" />
                        </ProtectedRoute>
                    }
                />
                <Route
                    path="/chat"
                    element={
                        <ProtectedRoute>
                            <ChatPage />
                        </ProtectedRoute>
                    }
                />
                <Route
                    path="/profile"
                    element={
                        <ProtectedRoute>
                            <ProfilePage />
                        </ProtectedRoute>
                    }
                />
            </Route>
        </Routes>
    );
}
