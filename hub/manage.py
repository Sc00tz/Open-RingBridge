"""Hub account admin from the command line, for when nobody can sign in.

  docker compose exec hub python hub/manage.py users
  docker compose exec hub python hub/manage.py adduser NAME [--admin]
  docker compose exec hub python hub/manage.py passwd NAME
"""
import argparse
import getpass
import os
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from accounts import AccountError, Accounts  # noqa: E402

DATA = pathlib.Path(os.environ.get("HUB_DATA", pathlib.Path(__file__).resolve().parent / "data"))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("users")
    add = sub.add_parser("adduser")
    add.add_argument("name")
    add.add_argument("--admin", action="store_true")
    pw = sub.add_parser("passwd")
    pw.add_argument("name")
    args = parser.parse_args()

    DATA.mkdir(parents=True, exist_ok=True)
    accounts = Accounts(DATA / "accounts.db")
    try:
        if args.cmd == "users":
            for u in accounts.users():
                print(f"{u['id']:>3}  {u['username']}{'  (admin)' if u['is_admin'] else ''}")
        elif args.cmd == "adduser":
            user = accounts.create_user(args.name, getpass.getpass("Password: "), is_admin=args.admin)
            print(f"Added {user['username']}.")
        elif args.cmd == "passwd":
            match = [u for u in accounts.users() if u["username"].lower() == args.name.lower()]
            if not match:
                sys.exit(f"No account named {args.name}.")
            accounts.set_password(match[0]["id"], getpass.getpass("New password: "))
            print("Password changed. Their browsers and phones are signed out.")
    except AccountError as e:
        sys.exit(str(e))


if __name__ == "__main__":
    main()
