import { NavLink, Route, Routes } from 'react-router-dom';
import Reports from './pages/Reports';
import Applications from './pages/Applications';
import ConfigPage from './pages/ConfigPage';
import Editor from './pages/Editor';
import Home from './pages/Home';
import Search from './pages/Search';

export default function App() {
  return (
    <div className="app">
      <aside className="sidebar">
        <h1>Project Gamma</h1>
        <nav>
          <NavLink to="/" end>Overview</NavLink>
          <NavLink to="/search">Search & queue</NavLink>
          <NavLink to="/reports">Reports</NavLink>
          <NavLink to="/applications">Applications</NavLink>
          <NavLink to="/editor">Workflow editor</NavLink>
          <NavLink to="/config">Config</NavLink>
        </nav>
      </aside>
      <main className="main">
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/search" element={<Search />} />
          <Route path="/reports" element={<Reports />} />
          <Route path="/applications" element={<Applications />} />
          <Route path="/editor" element={<Editor />} />
          <Route path="/config" element={<ConfigPage />} />
        </Routes>
      </main>
    </div>
  );
}
