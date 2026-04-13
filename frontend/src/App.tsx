import React from 'react';
import { Toaster } from 'sonner';
import { BrowserRouter } from 'react-router-dom';
import AppRouter from './route';

const App: React.FC = () => {
    return (
        <>
            <Toaster position="top-center" richColors />
            <BrowserRouter>
                <AppRouter />
            </BrowserRouter>
        </>
    );
};

export default App;
