import { Navigate, Route, Routes } from "react-router-dom";
import HomePage from "../pages/Home";
import SpectralDataPage from "../pages/SpectralData";
import SpectralDataManagementPage from "../pages/SpectralDataManagement";
import LoginPage from "../pages/Login";
import RegisterPage from "../pages/Register";
import ProfilePage from "../pages/Profile";
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
                <Route path="/dashboard" 
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
                    path="/spectral-management"
                    element={
                        <ProtectedRoute>
                            <SpectralDataManagementPage />
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