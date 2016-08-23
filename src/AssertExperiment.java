import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseException;
import com.github.javaparser.ast.CompilationUnit;

/**
 * The purpose of this class is to reproduce a simple experiment measuring the
 * effect of assertions on commits. Specifically, whether or not methods
 * containing asserts have (relatively speaking) fewer or greater "bug fixing"
 * commits.
 * 
 * @author David J. Pearce
 *
 */
public class AssertExperiment {

	private static String[] repositories = { "file:///Users/djp/projects/Jasm/" };

	public static void main(String[] args) {
		DisplayLog log = new DisplayLog(System.out);

		try {
			for (String repo : repositories) {
				Git git = cloneGitRepository(repo, log);
				List<RevCommit> fixes = extractBugFixCommits(git, log);
				classifyCommits(fixes, git, log);
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * Clone a given git repository from a URI into a temporary directory. The
	 * URI may refer to a remote location, such as a repository on GitHub or on
	 * BitBucket.
	 * 
	 * @param URI
	 * @param log
	 * @return
	 * @throws IOException
	 * @throws InvalidRemoteException
	 * @throws TransportException
	 * @throws GitAPIException
	 */
	private static Git cloneGitRepository(String URI, DisplayLog log)
			throws IOException, InvalidRemoteException, TransportException, GitAPIException {
		log.startActivity("Cloning repository " + URI);
		// Create a new temporary directory
		File tmpdir = File.createTempFile("tmp", "");
		// Delete that directory, should it already exist.
		tmpdir.delete();
		// Clone the repository into the given directory
		Git git = Git.cloneRepository().setURI(URI).setDirectory(tmpdir).call();
		// Done
		log.endActivity();
		return git;
	}

	/**
	 * Traverse all commits in this repository and extract those which are
	 * considered to be "bug fixes". These are commits with the word "fix" in
	 * their short message.
	 * 
	 * @param git
	 * @param log
	 * @throws NoHeadException
	 * @throws GitAPIException
	 */
	public static List<RevCommit> extractBugFixCommits(Git git, DisplayLog log)
			throws NoHeadException, GitAPIException {
		log.startActivity("Extracting commits");
		// Get the log of all commits
		Iterable<RevCommit> revlog = git.log().call();
		// Iterate and classify each commit
		ArrayList<RevCommit> commits = new ArrayList<RevCommit>();
		for (RevCommit rc : revlog) {
			if (isBugFixCommit(rc)) {
				commits.add(rc);
			}
		}
		// Done
		log.endActivity();
		return commits;
	}

	/**
	 * Determine whether or not a given commit is a "bug fix" or not. That is,
	 * whether or not it contains the word "fix" in its short message.
	 * 
	 * @param rc
	 * @return
	 */
	private static boolean isBugFixCommit(RevCommit rc) {
		String msg = rc.getShortMessage().toLowerCase();
		return msg.contains("fix");
	}

	/**
	 * Classify a given list of commits. This requires extracting the diffs for
	 * each commit, parsing the affected Java source files (if any), locating
	 * the enclosing method (if any) and determining whether or not that method
	 * includes an instance of the assert statement.
	 * 
	 * @param commits
	 * @param git
	 * @param log
	 * @throws IncorrectObjectTypeException
	 * @throws IOException
	 * @throws GitAPIException
	 */
	private static void classifyCommits(List<RevCommit> commits, Git git, DisplayLog log)
			throws IncorrectObjectTypeException, IOException, GitAPIException {
		log.startActivity("Classifying bug fixes");
		Repository repository = git.getRepository();		
		//
		for (RevCommit rc : commits) {
			List<DiffEntry> diffs = extractDiffs(rc, git, log);
			for (DiffEntry diff : diffs) {
				// FIXME: this is not efficient as reparses file for every diff
				if (diff.getNewPath().endsWith(".java")) {
					ObjectLoader loader = repository.open(diff.getNewId().toObjectId());
					byte[] bytes = loader.getBytes();
					ByteArrayInputStream bin = new ByteArrayInputStream(bytes);
					try {
						CompilationUnit jf = JavaParser.parse(bin);
						System.out.println("PARSED: " + diff.getNewPath());
					} catch (ParseException e) {
						e.printStackTrace();
					}
				}
			}
		}
		log.endActivity();
	}

	/**
	 * Extract the list of diffs that a given commit represents.
	 * 
	 * @param commit
	 * @param git
	 * @param log
	 * @return
	 * @throws IncorrectObjectTypeException
	 * @throws IOException
	 * @throws GitAPIException
	 */
	private static List<DiffEntry> extractDiffs(RevCommit commit, Git git, DisplayLog log)
			throws IncorrectObjectTypeException, IOException, GitAPIException {
		ArrayList<DiffEntry> diffs = new ArrayList<DiffEntry>();
		Repository repository = git.getRepository();
		RevTree rt = commit.getTree();
		for (RevCommit parent : commit.getParents()) {
			try (ObjectReader reader = repository.newObjectReader()) {
				CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
				oldTreeIter.reset(reader, parent.getTree().getId());
				CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
				newTreeIter.reset(reader, rt.getId());
				diffs.addAll(git.diff().setNewTree(newTreeIter).setOldTree(oldTreeIter).call());
			}
		}
		return diffs;
	}
}
